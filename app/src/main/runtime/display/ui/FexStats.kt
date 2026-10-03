package com.winlator.cmod.runtime.display.ui

import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FilenameFilter
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets

internal class FexStats(imageFsRoot: File) {

  class EventCounts {
    private var count = 0L
    private var lastSampleCount = 0L
    private var averageSec = 0.0
    private var lastChronoNs = 0L

    fun account(total: Long, nowNs: Long) {
      count = total
      lastSampleCount += total
      val diff = nowNs - lastChronoNs
      if (diff >= 1000000000L) {
        // Average over the last second (same formula as upstream).
        averageSec = lastSampleCount * (diff / NANOS_IN_SECONDS)
        lastSampleCount = 0
        lastChronoNs = nowNs
      }
    }

    fun accountTime(nowNs: Long) {
      lastChronoNs = nowNs
    }

    fun count() = count

    fun avg() = averageSec
  }

  private class RetainedStats {
    var lastSeenNs = 0L
    val previous = LongArray(FIELD_COUNT)
    val current = LongArray(FIELD_COUNT)
  }

  @JvmField var status = "Not Found!"
  private var fexVersion = ""

  /** Pid parsed out of the tracked "fex-<pid>-stats" file name, and its process name. */
  @JvmField var trackedPid = -1
  @JvmField var processName = ""
  @JvmField val loadData = FloatArray(LOAD_SAMPLES) // fex_load_data (shifted window)
  @JvmField val sigbusCounts = EventCounts()
  @JvmField val smcCounts = EventCounts()
  @JvmField val softfloatCounts = EventCounts()
  @JvmField val cacheMissCounts = EventCounts()
  @JvmField val diskCacheHitCounts = EventCounts()
  @JvmField val diskCacheMissCounts = EventCounts()

  private val shmDirs: Array<File> =
      arrayOf(
          File(imageFsRoot, "tmp"),
          File(imageFsRoot, "usr/tmp"),
          File(imageFsRoot, "dev/shm"),
      )
  private val cycleCounterFrequency = detectCycleCounterFrequency()
  private val hardwareConcurrency = maxOf(1, Runtime.getRuntime().availableProcessors())
  private val pageSize = detectPageSize()

  @JvmField val threadLoads = FloatArray(hardwareConcurrency)
  @JvmField var threadLoadCount = 0

  private var trackedFile: File? = null
  private var trackedRaf: RandomAccessFile? = null
  private var trackedChannel: FileChannel? = null
  private var shm: ByteBuffer? = null // mapped stats region, little-endian
  private var shmSize = 0L
  private var trackedThreadStatsSize = THREAD_STATS_SIZE
  private var firstSample = true
  private var previousSamplePeriodNs = 0L
  private val sampledStats = HashMap<Int, RetainedStats>()
  private val hottestThreads = ArrayList<Long>()

  fun isPidFound() = trackedFile != null

  fun appType(): String {
    val buffer = shm ?: return "Unknown"
    return when (buffer.get(HDR_APP_TYPE).toInt()) {
      APP_LINUX_32 -> "Linux32"
      APP_LINUX_64 -> "Linux64"
      APP_WIN_ARM64EC -> "arm64ec"
      APP_WIN_WOW64 -> "wow64"
      else -> "Unknown"
    }
  }

  /** Call it on a regular cadence from the stats thread. */
  @Synchronized
  fun update() {
    try {
      updateLocked()
    } catch (e: Exception) {
      Log.w(TAG, "FEX stats sampling failed", e)
      destroyShm()
      status = "Not Found!"
    }
  }

  /** Release the mapping; the next update() rescans from scratch. */
  @Synchronized
  fun close() {
    destroyShm()
    status = "Not Found!"
  }

  private fun updateLocked() {
    if (trackedFile?.isFile == false) {
      destroyShm()
      status = "Not Found!"
    }
    val newest = findNewestStatsFile()
    if (newest == null) {
      if (trackedFile != null) destroyShm()
      status = "Not Found!"
      return
    }
    if (newest != trackedFile) {
      openStatsFile(newest)
      if (trackedFile == null) return
    }

    // If the SHM changed size then remap with the new size; FEX grows the region
    // to fit more threads without invalidating previous thread data.
    if (!checkShmUpdateNecessary()) return

    // FEX publishes the relaxed SHM atomics with an ARM store barrier. Pair it
    // with an acquire barrier before walking the list.
    nativeMemoryBarrier()

    // The open can race the process' exec; retry the name lookup until it sticks.
    if (processName.isEmpty() && trackedPid > 0) {
      processName = readProcessName(trackedPid)
    }

    val nowNs = System.nanoTime()

    // Sample the thread stats linked list. FEX updates these constantly, so
    // sampling quickly makes us a loose sampling profiler.
    val maxIterations = shmSize / 16 // strict superset of any valid walk
    var headerOffset = readU32(HDR_HEAD)
    var iterations = 0L
    while (headerOffset != 0L && iterations < maxIterations) {
      // The whole slot must fit; a torn offset near the end would otherwise
      // throw past the ByteBuffer bounds (upstream just reads the mmap page).
      val slotSize = minOf(trackedThreadStatsSize, THREAD_STATS_SIZE)
      if (headerOffset + slotSize > shmSize) break
      val base = headerOffset.toInt()
      val tid = readU32(base + TS_TID).toInt()
      if (tid != 0) {
        val retained = sampledStats.getOrPut(tid) { RetainedStats() }
        copyThreadStats(retained.current, base)
        retained.lastSeenNs = nowNs
      }
      headerOffset = readU32(base + TS_NEXT)
      iterations++
    }

    if (firstSample) {
      // Skip the first sample, it would look crazy.
      firstSample = false
      status = "Accumulating"
      return
    }

    status = fexVersion

    // Accumulate full JIT time.
    var totalJitTime = 0L
    var totalSigbusEvents = 0L
    var totalSmcEvents = 0L
    var totalSoftfloatEvents = 0L
    var totalCacheMisses = 0L
    var totalDiskCacheHits = 0L
    var totalDiskCacheMisses = 0L
    var threadsSampled = 0
    hottestThreads.clear()
    val it = sampledStats.entries.iterator()
    while (it.hasNext()) {
      val retained = it.next().value
      threadsSampled++
      val totalTime =
          counterDelta(retained.current[0], retained.previous[0]) +
              counterDelta(retained.current[1], retained.previous[1])
      totalSigbusEvents += counterDelta(retained.current[2], retained.previous[2])
      totalSmcEvents += counterDelta(retained.current[3], retained.previous[3])
      totalSoftfloatEvents += counterDelta(retained.current[4], retained.previous[4])
      totalCacheMisses += counterDelta(retained.current[5], retained.previous[5])
      totalDiskCacheHits += counterDelta(retained.current[6], retained.previous[6])
      totalDiskCacheMisses += counterDelta(retained.current[7], retained.previous[7])
      System.arraycopy(retained.current, 0, retained.previous, 0, FIELD_COUNT)
      totalJitTime += totalTime
      if (nowNs - retained.lastSeenNs >= MAXIMUM_THREAD_WAIT_NS) {
        it.remove()
        continue
      }
      hottestThreads.add(totalTime)
    }

    hottestThreads.sortDescending()

    // Calculate loads based on the sample period. FEX-Emu counts cycles, so the
    // load is the used cycles over the cycles the period could possibly have.
    val samplePeriodNs = nowNs - previousSamplePeriodNs
    val maxCyclesInSamplePeriod = cycleCounterFrequency * (samplePeriodNs / NANOS_IN_SECONDS)
    val maxCoresThreadsPossible = minOf(hardwareConcurrency, threadsSampled)

    val fexLoad =
        if (maxCyclesInSamplePeriod > 0 && maxCoresThreadsPossible > 0) {
          totalJitTime / (maxCyclesInSamplePeriod * maxCoresThreadsPossible) * 100.0
        } else {
          0.0
        }

    // Top thread loads: only ever show up to how many hardware threads exist.
    val hotCount = minOf(hardwareConcurrency, hottestThreads.size)
    for (i in 0 until hotCount) {
      threadLoads[i] =
          if (maxCyclesInSamplePeriod > 0) {
            (hottestThreads[i] / maxCyclesInSamplePeriod * 100.0).toFloat()
          } else {
            0f
          }
    }
    threadLoadCount = hotCount

    sigbusCounts.account(totalSigbusEvents, nowNs)
    smcCounts.account(totalSmcEvents, nowNs)
    softfloatCounts.account(totalSoftfloatEvents, nowNs)
    cacheMissCounts.account(totalCacheMisses, nowNs)
    diskCacheHitCounts.account(totalDiskCacheHits, nowNs)
    diskCacheMissCounts.account(totalDiskCacheMisses, nowNs)

    previousSamplePeriodNs = nowNs

    System.arraycopy(loadData, 1, loadData, 0, LOAD_SAMPLES - 1)
    loadData[LOAD_SAMPLES - 1] = fexLoad.toFloat()
  }

  // ── shm file management ──────────────────────────────────────────

  private fun findNewestStatsFile(): File? {
    var best: File? = null
    for (dir in shmDirs) {
      val entries = dir.listFiles(STATS_FILTER) ?: continue
      for (entry in entries) {
        // shm_unlink normally removes the object when FEX exits, but a
        // file-backed Android shim can leave stale objects after a crash.
        // Never attach the HUD to one of those just because it is newest.
        val pid = pidFromStatsFile(entry)
        if (pid <= 0 || !File("/proc/$pid").isDirectory) continue
        if (best == null || entry.lastModified() > best.lastModified()) best = entry
      }
    }
    return best
  }

  /** Map and validate the object, reset all sampling state. */
  private fun openStatsFile(file: File) {
    destroyShm()
    var raf: RandomAccessFile? = null
    try {
      raf = RandomAccessFile(file, "r")
      val channel = raf.channel
      val fileSize = channel.size()
      if (fileSize < HEADER_SIZE) throw IOException("stats file too small")
      val mapSize = alignUp(fileSize, pageSize)
      val map = channel.map(FileChannel.MapMode.READ_ONLY, 0, mapSize)
      map.order(ByteOrder.LITTLE_ENDIAN)
      nativeMemoryBarrier()
      if ((map.get(HDR_VERSION).toInt() and 0xFF) != FEX_STATS_VERSION) {
        // Version read doesn't match the implementation, we can't read.
        closeQuietly(raf)
        destroyShm()
        status = "version mismatch"
        return
      }

      trackedFile = file
      trackedRaf = raf
      trackedChannel = channel
      shm = map
      shmSize = mapSize
      trackedThreadStatsSize = readThreadStatsSize()
      trackedPid = pidFromStatsFile(file)
      processName = readProcessName(trackedPid)
      previousSamplePeriodNs = System.nanoTime()
      firstSample = true
      sampledStats.clear()

      fexVersion = readVersionString(map)
      sigbusCounts.accountTime(previousSamplePeriodNs)
      smcCounts.accountTime(previousSamplePeriodNs)
      softfloatCounts.accountTime(previousSamplePeriodNs)
      cacheMissCounts.accountTime(previousSamplePeriodNs)
      diskCacheHitCounts.accountTime(previousSamplePeriodNs)
      diskCacheMissCounts.accountTime(previousSamplePeriodNs)
      loadData.fill(0f)
      threadLoadCount = 0
    } catch (e: Exception) {
      Log.w(TAG, "Failed to map $file", e)
      status = "Not Found!"
      closeQuietly(raf)
      destroyShm()
    }
  }

  private fun destroyShm() {
    shm = null
    shmSize = 0
    closeQuietly(trackedRaf)
    trackedRaf = null
    trackedChannel = null
    trackedFile = null
    trackedPid = -1
    processName = ""
    sampledStats.clear()
    threadLoadCount = 0
  }

  private fun checkShmUpdateNecessary(): Boolean {
    nativeMemoryBarrier()
    val newSize = alignUp(readU32(HDR_SIZE), pageSize)
    if (newSize == shmSize) return true
    if (newSize < HEADER_SIZE) return true // header not fully written yet
    val channel = trackedChannel ?: return false
    return try {
      val remapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, newSize)
      remapped.order(ByteOrder.LITTLE_ENDIAN)
      shm = remapped
      shmSize = newSize
      trackedThreadStatsSize = readThreadStatsSize()
      true
    } catch (e: Exception) {
      Log.w(TAG, "Failed to remap stats shm", e)
      destroyShm()
      status = "Not Found!"
      false
    }
  }

  private fun readU32(pos: Int): Long {
    val buffer = shm ?: return 0
    return buffer.getInt(pos).toLong() and 0xFFFFFFFFL
  }

  private fun readThreadStatsSize(): Int {
    val buffer = shm ?: return THREAD_STATS_SIZE
    val provided = buffer.getShort(HDR_THREAD_STATS_SIZE).toInt() and 0xFFFF
    return if (provided == 0) THREAD_STATS_SLOT_SIZE else minOf(provided, THREAD_STATS_SIZE)
  }

  /** 16-byte-chunk copy semantics of atomic_copy_thread_stats(). */
  private fun copyThreadStats(dest: LongArray, base: Int) {
    val buffer = shm ?: return
    dest.fill(0L)
    if (nativeCopyThreadStats(buffer, base, trackedThreadStatsSize, dest)) return

    // The native path is expected for a direct mapped buffer. Keep a guarded
    // fallback for unusual Android buffer implementations.
    if (TS_JIT_TIME < trackedThreadStatsSize) dest[0] = buffer.getLong(base + TS_JIT_TIME)
    if (TS_SIGNAL_TIME < trackedThreadStatsSize) dest[1] = buffer.getLong(base + TS_SIGNAL_TIME)
    if (TS_SIGBUS_COUNT < trackedThreadStatsSize) dest[2] = buffer.getLong(base + TS_SIGBUS_COUNT)
    if (TS_SMC_EVENTS < trackedThreadStatsSize) dest[3] = buffer.getLong(base + TS_SMC_EVENTS)
    if (TS_SOFTFLOAT_COUNT < trackedThreadStatsSize) {
      dest[4] = buffer.getLong(base + TS_SOFTFLOAT_COUNT)
    }
    if (TS_CACHE_MISS_COUNT < trackedThreadStatsSize) {
      dest[5] = buffer.getLong(base + TS_CACHE_MISS_COUNT)
    }
    if (TS_DISK_CACHE_HIT_COUNT < trackedThreadStatsSize) {
      dest[6] = buffer.getLong(base + TS_DISK_CACHE_HIT_COUNT)
    }
    if (TS_DISK_CACHE_MISS_COUNT < trackedThreadStatsSize) {
      dest[7] = buffer.getLong(base + TS_DISK_CACHE_MISS_COUNT)
    }
  }

  private fun counterDelta(current: Long, previous: Long): Long {
    // FEX counters are monotonic, but a process restart or an inconsistent
    // observation during publication must not become a giant unsigned delta.
    return if (current >= previous) current - previous else 0L
  }

  // ── process identity ─────────────────────────────────────────────

  /** "fex-<pid>-stats" -> pid. */
  private fun pidFromStatsFile(file: File): Int {
    val name = file.name
    return name.substring(STATS_PREFIX.length, name.length - STATS_SUFFIX.length).toIntOrNull()
        ?: -1
  }

  /**
   * Display name of the process owning the stats shm, from /proc/<pid>/cmdline.
   * argv is NUL-separated; for wine the guest exe is argv[1], otherwise argv[0]
   * is the best we have. Empty when the process is gone or unreadable.
   */
  private fun readProcessName(pid: Int): String {
    if (pid <= 0) return ""
    return try {
      val args =
          String(File("/proc/$pid/cmdline").readBytes(), StandardCharsets.US_ASCII)
              .split('\u0000')
              .filter { it.isNotEmpty() }
      if (args.isEmpty()) return ""
      val candidate = if (args.size >= 2 && args[0].endsWith("wine")) args[1] else args[0]
      candidate.substringAfterLast('/').substringAfterLast('\\').take(MAX_PROCESS_NAME_LEN)
    } catch (e: Exception) {
      ""
    }
  }

  // ── native glue ───

  private external fun nativeCycleCounterFrequency(): Long
  private external fun nativeMemoryBarrier()
  private external fun nativeCopyThreadStats(
      buffer: ByteBuffer, base: Int, statsSize: Int, dest: LongArray): Boolean

  private fun detectCycleCounterFrequency(): Long {
    return try {
      System.loadLibrary("winlator")
      val freq = nativeCycleCounterFrequency()
      if (freq > 0) {
        freq
      } else {
        Log.w(TAG, "CNTFRQ_EL0 read as zero; FEX load percentages will stay at 0")
        0
      }
    } catch (t: Throwable) {
      Log.w(TAG, "native cycle counter reader unavailable", t)
      0
    }
  }

  companion object {
    private const val TAG = "FexStats"

    // fex_stats_header layout (64 bytes total).
    private const val HDR_VERSION = 0 // u8
    private const val HDR_APP_TYPE = 1 // u8
    private const val HDR_THREAD_STATS_SIZE = 2 // u16
    private const val HDR_FEX_VERSION = 4 // char[48]
    private const val HDR_FEX_VERSION_LEN = 48
    private const val HDR_HEAD = 52 // atomic u32: offset of first thread stats
    private const val HDR_SIZE = 56 // atomic u32: current shm size
    private const val HEADER_SIZE = 64

    // fex_thread_stats layout; FEX may append fields, so older structures remain valid.
    private const val TS_NEXT = 0 // atomic u32
    private const val TS_TID = 4 // atomic u32
    private const val TS_JIT_TIME = 8 // u64, CNTVCT_EL0 cycles
    private const val TS_SIGNAL_TIME = 16 // u64, CNTVCT_EL0 cycles
    private const val TS_SIGBUS_COUNT = 24 // u64
    private const val TS_SMC_EVENTS = 32 // u64
    private const val TS_SOFTFLOAT_COUNT = 40 // u64
    private const val TS_CACHE_MISS_COUNT = 48 // u64
    private const val TS_DISK_CACHE_HIT_COUNT = 80 // u64
    private const val TS_DISK_CACHE_MISS_COUNT = 88 // u64
    private const val THREAD_STATS_SLOT_SIZE = 112
    private const val THREAD_STATS_SIZE = 112
    private const val FIELD_COUNT = 8

    private const val FEX_STATS_VERSION = 2
    private const val MAXIMUM_THREAD_WAIT_NS = 10000000000L // 10s
    const val LOAD_SAMPLES = 200
    private const val NANOS_IN_SECONDS = 1000000000.0

    // AppType enum values (fex.cpp).
    private const val APP_LINUX_32 = 0
    private const val APP_LINUX_64 = 1
    private const val APP_WIN_ARM64EC = 2
    private const val APP_WIN_WOW64 = 3

    private const val STATS_PREFIX = "fex-"
    private const val STATS_SUFFIX = "-stats"
    private const val MAX_PROCESS_NAME_LEN = 20
    private val STATS_FILTER =
        FilenameFilter { _, name ->
          if (!name.startsWith(STATS_PREFIX) || !name.endsWith(STATS_SUFFIX)) {
            false
          } else {
            var digitsOnly = true
            for (i in STATS_PREFIX.length until name.length - STATS_SUFFIX.length) {
              if (!name[i].isDigit()) {
                digitsOnly = false
                break
              }
            }
            digitsOnly
          }
        }

    private fun closeQuietly(raf: RandomAccessFile?) {
      try {
        raf?.close()
      } catch (ignored: Exception) {
      }
    }

    private fun readVersionString(buf: ByteBuffer): String {
      val bytes = ByteArray(HDR_FEX_VERSION_LEN)
      var len = 0
      while (len < HDR_FEX_VERSION_LEN) {
        val b = buf.get(HDR_FEX_VERSION + len)
        if (b.toInt() == 0) break
        bytes[len] = b
        len++
      }
      return String(bytes, 0, len, StandardCharsets.US_ASCII)
    }

    private fun alignUp(value: Long, alignment: Long): Long {
      return value + (alignment - value % alignment) % alignment
    }

    private fun detectPageSize(): Long {
      return try {
        val size = Os.sysconf(OsConstants._SC_PAGESIZE)
        if (size > 0) size else 4096
      } catch (t: Throwable) {
        4096
      }
    }
  }
}

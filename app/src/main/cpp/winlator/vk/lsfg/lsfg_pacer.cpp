// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "lsfg_pacer.hpp"

#include <algorithm>
#include <cmath>

namespace lsfg {

namespace {

using Clock = std::chrono::steady_clock;

constexpr float INTERVAL_SMOOTHING = 0.25f;
constexpr float SOURCE_SMOOTHING = 0.15f;
constexpr float SOURCE_STALE_SECONDS = 0.5f;
constexpr float DISCONTINUITY_SECONDS = 0.25f;
constexpr float HEADROOM_EPSILON = 0.02f;
constexpr float CREDIT_EPSILON = 1.0e-4f;
constexpr float SOURCE_ACCUM_FLOOR = 0.01f;
constexpr uint32_t MIN_RATE_SAMPLES = 12;
constexpr float COST_RAISE_SECONDS = 0.25f;
constexpr float COST_PROBE_SECONDS = 0.5f;
constexpr float COST_BLAME_SECONDS = 1.25f;
constexpr float BASELINE_SMOOTHING = 0.25f;
constexpr float RATE_JUMP_HIGH = 1.4f;
constexpr float RATE_JUMP_LOW = 0.7f;
constexpr uint32_t RATE_JUMP_SAMPLES = 3;
constexpr float COST_RECOVER_RATIO = 0.97f;
constexpr float COST_HOLD_SECONDS = 5.0f;
constexpr float COST_DROP_RATIO = 0.9f;

}

size_t LsfgPacer::MaxGenerations() const {
    if (config.multiplier < 2) return 0;
    if (config.target_rate != 0) return LSFG_MAX_MULTIPLIER - 1;
    return std::min<size_t>(config.multiplier, LSFG_MAX_MULTIPLIER) - 1;
}

void LsfgPacer::TrackSourceRate(Clock::time_point now, uint64_t source_frames) {
    if (!last_source_sample) {
        last_source_sample = now;
        last_source_frames = source_frames;
        return;
    }

    const float elapsed = std::chrono::duration<float>(now - *last_source_sample).count();
    if (elapsed <= 0.0f) {
        return;
    }

    last_source_sample = now;
    const uint64_t drawn =
        source_frames > last_source_frames ? source_frames - last_source_frames : 0;
    last_source_frames = source_frames;

    if (elapsed > SOURCE_STALE_SECONDS) {
        source_frame_accum = 0.0f;
        source_time_accum = 0.0f;
        source_interval = 0.0f;
        source_samples = 0;
        return;
    }

    last_drawn = drawn;
    last_elapsed = elapsed;

    const float instant = drawn > 0 ? elapsed / static_cast<float>(drawn) : 0.0f;
    if (instant > 0.0f && source_interval > 0.0f &&
        (instant > source_interval * RATE_JUMP_HIGH || instant < source_interval * RATE_JUMP_LOW)) {
        if (++rate_jumps >= RATE_JUMP_SAMPLES) {
            source_frame_accum = static_cast<float>(drawn);
            source_time_accum = elapsed;
            source_interval = instant;
            source_samples = MIN_RATE_SAMPLES;
            rate_jumps = 0;
            return;
        }
    } else {
        rate_jumps = 0;
    }

    source_frame_accum += (static_cast<float>(drawn) - source_frame_accum) * SOURCE_SMOOTHING;
    source_time_accum += (elapsed - source_time_accum) * SOURCE_SMOOTHING;
    source_interval =
        source_frame_accum > SOURCE_ACCUM_FLOOR ? source_time_accum / source_frame_accum : 0.0f;
    if (source_samples < MIN_RATE_SAMPLES) ++source_samples;
}

void LsfgPacer::TrackLoopRate(float interval_seconds) {
    loop_interval = loop_interval > 0.0f
                        ? loop_interval + (interval_seconds - loop_interval) * INTERVAL_SMOOTHING
                        : interval_seconds;
    if (loop_samples < MIN_RATE_SAMPLES) ++loop_samples;
}

bool LsfgPacer::RatesSettled() const {
    return source_samples >= MIN_RATE_SAMPLES && loop_samples >= MIN_RATE_SAMPLES;
}

float LsfgPacer::SourceInterval() const {
    if (source_samples >= MIN_RATE_SAMPLES && source_interval > 0.0f) return source_interval;
    return config.source_rate > 0.0f ? 1.0f / config.source_rate : 0.0f;
}

size_t LsfgPacer::HeadroomLimit() const {
    const float interval = SourceInterval();
    if (config.refresh_rate <= 0.0f || interval <= 0.0f) {
        return LSFG_MAX_MULTIPLIER - 1;
    }

    const float budget = std::ceil(config.refresh_rate * interval - HEADROOM_EPSILON);
    return budget < 2.0f ? 0 : static_cast<size_t>(budget) - 1;
}

size_t LsfgPacer::SlotLimit() const {
    const float interval = SourceInterval();
    if (config.refresh_rate <= 0.0f || interval <= 0.0f) {
        return LSFG_MAX_MULTIPLIER - 1;
    }

    const float slots = std::floor(config.refresh_rate * interval + HEADROOM_EPSILON);
    return slots < 2.0f ? 0 : static_cast<size_t>(slots) - 1;
}

void LsfgPacer::TrackCost(Clock::time_point now, size_t ceiling) {
    const float interval = SourceInterval();
    if (interval <= 0.0f || !RatesSettled()) return;
    const float rate = 1.0f / interval;

    if (!last_cost_change) {
        last_cost_change = now;
        rate_at_raise = rate;
        return;
    }

    const float since = std::chrono::duration<float>(now - *last_cost_change).count();

    if (probing) {
        if (since < COST_PROBE_SECONDS) return;
        if (rate >= rate_before_probe * COST_RECOVER_RATIO) {
            raise_delay = COST_HOLD_SECONDS;
        } else {
            cost_limit = probe_from;
        }
        probing = false;
        rate_at_raise = rate;
        last_cost_change = now;
        return;
    }

    if (rate_at_raise > 0.0f && rate < rate_at_raise * COST_DROP_RATIO) {
        if (cost_limit > 0 && since <= COST_BLAME_SECONDS) {
            probe_from = cost_limit;
            rate_before_probe = rate_at_raise;
            cost_limit--;
            probing = true;
        } else {
            raise_delay = COST_RAISE_SECONDS;
        }
        rate_at_raise = rate;
        last_cost_change = now;
        return;
    }

    rate_at_raise += (rate - rate_at_raise) * BASELINE_SMOOTHING;
    if (since < raise_delay || cost_limit >= ceiling || limit < cost_limit) return;

    float target = static_cast<float>(config.target_rate);
    if (target > 0.0f && config.refresh_rate > 0.0f) {
        target = std::min(target, config.refresh_rate);
    }
    const float wanted = target > 0.0f ? target * interval
                                       : static_cast<float>(MaxGenerations()) + 1.0f;
    if (wanted <= static_cast<float>(cost_limit) + 1.0f + HEADROOM_EPSILON) return;

    cost_limit++;
    raise_delay = COST_RAISE_SECONDS;
    rate_at_raise = rate;
    last_cost_change = now;
}

LsfgPlan LsfgPacer::Plan(size_t capacity, uint64_t source_frames) {
    const size_t ceiling = std::min(capacity, MaxGenerations());
    if (ceiling == 0) {
        Reset();
        return {};
    }

    const Clock::time_point now = Clock::now();
    TrackSourceRate(now, source_frames);
    if (!last_frame) {
        last_frame = now;
        return {};
    }

    const float interval_seconds = std::chrono::duration<float>(now - *last_frame).count();
    last_frame = now;

    if (interval_seconds <= 0.0f || interval_seconds > DISCONTINUITY_SECONDS) {
        output_credit = 0.0f;
        return LsfgPlan{0, true};
    }

    TrackLoopRate(interval_seconds);

    float target_rate = static_cast<float>(config.target_rate);
    if (target_rate > 0.0f && config.refresh_rate > 0.0f) {
        target_rate = std::min(target_rate, config.refresh_rate);
    }

    if (target_rate == 0.0f) {
        output_credit = 0.0f;
        TrackCost(now, ceiling);
        limit = std::min(std::min(ceiling, SlotLimit()), cost_limit);
        return LsfgPlan{limit, true};
    }

    TrackCost(now, ceiling);
    const size_t allowed = std::min(std::min(ceiling, HeadroomLimit()), cost_limit);
    const float pace_interval = SourceInterval() > 0.0f ? SourceInterval() : loop_interval;
    const float desired_outputs = pace_interval * target_rate;
    if (allowed == 0 || desired_outputs <= 1.0f) {
        output_credit = 0.0f;
        limit = 0;
        return {};
    }

    output_credit += desired_outputs;
    const size_t outputs =
        std::max<size_t>(1, static_cast<size_t>(std::floor(output_credit + CREDIT_EPSILON)));
    const size_t generations = std::min(outputs - 1, allowed);

    output_credit -= static_cast<float>(generations + 1);
    if (output_credit < 0.0f) {
        output_credit = 0.0f;
    } else if (generations == allowed && output_credit >= 1.0f) {
        output_credit = std::fmod(output_credit, 1.0f);
    }

    limit = generations;
    return LsfgPlan{generations, true};
}

LsfgPacerStats LsfgPacer::Stats() const {
    LsfgPacerStats stats;
    stats.source_rate = source_interval > 0.0f ? 1.0f / source_interval : 0.0f;
    stats.loop_rate = loop_interval > 0.0f ? 1.0f / loop_interval : 0.0f;
    stats.refresh_rate = config.refresh_rate;
    stats.target_rate = static_cast<float>(config.target_rate);
    stats.slots = config.refresh_rate * source_interval;
    stats.limit = limit;
    stats.cost_limit = cost_limit;
    stats.rates_settled = RatesSettled();
    stats.last_drawn = last_drawn;
    stats.last_elapsed = last_elapsed;
    stats.source_frames = last_source_frames;
    return stats;
}

void LsfgPacer::Reset() {
    last_frame.reset();
    last_source_sample.reset();
    last_source_frames = 0;
    source_interval = 0.0f;
    source_frame_accum = 0.0f;
    source_time_accum = 0.0f;
    loop_interval = 0.0f;
    source_samples = 0;
    loop_samples = 0;
    last_drawn = 0;
    last_elapsed = 0.0f;
    rate_jumps = 0;
    output_credit = 0.0f;
    limit = 0;
    cost_limit = 0;
    probe_from = 0;
    probing = false;
    raise_delay = COST_RAISE_SECONDS;
    rate_at_raise = 0.0f;
    rate_before_probe = 0.0f;
    last_cost_change.reset();
}

}

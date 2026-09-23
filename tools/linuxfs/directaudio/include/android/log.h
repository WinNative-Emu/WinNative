/* The driver logs through Android's logger; in a Linux session that is the session log. */
#ifndef WN_ANDROID_LOG_H
#define WN_ANDROID_LOG_H
enum { ANDROID_LOG_VERBOSE = 2, ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR };
int __android_log_print(int prio, const char *tag, const char *fmt, ...) __attribute__((format(printf, 3, 4)));
#endif

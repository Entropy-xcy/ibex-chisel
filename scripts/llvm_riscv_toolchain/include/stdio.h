#ifndef IBEX_LLVM_RISCV_STDIO_H_
#define IBEX_LLVM_RISCV_STDIO_H_

#include <stdarg.h>

int printf(const char *fmt, ...);
int sprintf(char *str, const char *fmt, ...);
int putchar(int ch);

#endif

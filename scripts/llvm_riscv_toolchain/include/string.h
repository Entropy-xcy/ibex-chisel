#ifndef IBEX_LLVM_RISCV_STRING_H_
#define IBEX_LLVM_RISCV_STRING_H_

#include <stddef.h>

void *memcpy(void *dest, const void *src, size_t n);
void *memset(void *s, int c, size_t n);
size_t strlen(const char *s);
int strcmp(const char *s1, const char *s2);

#endif

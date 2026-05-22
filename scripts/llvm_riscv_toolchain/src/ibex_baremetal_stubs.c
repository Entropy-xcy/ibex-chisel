#include <stddef.h>
#include <stdint.h>

static uintptr_t heap_cursor;
static unsigned char heap[4096] __attribute__((aligned(16)));

void *malloc(size_t size) {
  uintptr_t base = (uintptr_t)heap;
  uintptr_t next = (base + heap_cursor + 15u) & ~(uintptr_t)15u;
  uintptr_t end = next + size;

  if (end > base + sizeof(heap)) {
    return NULL;
  }

  heap_cursor = end - base;
  return (void *)next;
}

void free(void *ptr) {
  (void)ptr;
}

void *calloc(size_t nmemb, size_t size) {
  size_t total = nmemb * size;
  unsigned char *ptr = malloc(total);

  if (ptr == NULL) {
    return NULL;
  }

  for (size_t i = 0; i < total; ++i) {
    ptr[i] = 0;
  }
  return ptr;
}

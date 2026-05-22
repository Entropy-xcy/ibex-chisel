OUTPUT_ARCH(riscv)
ENTRY(_start)

MEMORY
{
  ram   : ORIGIN = 0x00100000, LENGTH = 0x00100000
  stack : ORIGIN = 0x001f0000, LENGTH = 0x00001000
}

_stack_start = ORIGIN(stack) + LENGTH(stack);

SECTIONS
{
  .vectors : {
    . = ALIGN(4);
    KEEP(*(.vectors))
  } > ram

  .text : {
    . = ALIGN(4);
    *(.text.init)
    *(.text)
    *(.text.*)
  } > ram

  .rodata : {
    . = ALIGN(4);
    *(.rodata)
    *(.rodata.*)
  } > ram

  .data : {
    . = ALIGN(4);
    *(.sdata)
    *(.sdata.*)
    *(.data)
    *(.data.*)
  } > ram

  .bss : {
    . = ALIGN(4);
    *(.sbss)
    *(.sbss.*)
    *(.bss)
    *(.bss.*)
    *(COMMON)
  } > ram
}

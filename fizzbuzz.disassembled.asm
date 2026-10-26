# Reconstructed from machine code. Use delayed branching OFF in MARS.
.data 0x10010000
    .byte 70, 105, 122, 122, 0, 66, 117, 122, 122, 0

.text 0x00400000
.globl main
main:
    addiu $t1, $zero, 1
    addiu $t2, $zero, 101
L00400008:
    jal L0040001C
    jal L0040009C
    addi $t1, $t1, 1
    bne $t1, $t2, L00400008
    jal L004000AC
L0040001C:
    addi $sp, $sp, -8
    sw $ra, 0($sp)
    addiu $at, $zero, 3
    div $t1, $at
    mfhi $t3
    addiu $at, $zero, 5
    div $t1, $at
    mfhi $t4
    bne $t3, $zero, L00400044
    jal L00400064
L00400044:
    bne $t4, $zero, L00400050
    jal L00400078
    j L00400058
L00400050:
    beq $t3, $zero, L00400058
    jal L0040008C
L00400058:
    lw $ra, 0($sp)
    addi $sp, $sp, 8
    jr $ra
L00400064:
    addiu $v0, $zero, 4
    lui $a0, 4097
    ori $a0, $a0, 0
    syscall
    jr $ra
L00400078:
    addiu $v0, $zero, 4
    lui $a0, 4097
    ori $a0, $a0, 5
    syscall
    jr $ra
L0040008C:
    addiu $v0, $zero, 1
    addu $a0, $t1, $zero
    syscall
    jr $ra
L0040009C:
    addiu $v0, $zero, 11
    addiu $a0, $zero, 10
    syscall
    jr $ra
L004000AC:
    addiu $v0, $zero, 10
    syscall

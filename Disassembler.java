import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

public class Disassembler {
    private static final String[] REGISTERS = {
            "zero", "at", "v0", "v1", "a0", "a1", "a2", "a3",
            "t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7",
            "s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7",
            "t8", "t9", "k0", "k1", "gp", "sp", "fp", "ra"
    };

    public static void main(String[] args) {
        try {
            if (args.length > 2) {
                throw new IllegalArgumentException(
                        "Usage: java Disassembler [input.mhc-bin|input.mhc-ascii [output.asm]]");
            }
            Path input = Paths.get(args.length == 0 ? "fizzbuzz.mhc-bin" : args[0]);
            Path output = args.length == 2 ? Paths.get(args[1]) : defaultOutput(input);
            if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())
                    || (Files.exists(output) && Files.isSameFile(input, output))) {
                throw new IllegalArgumentException("Input and output must be different files.");
            }

            MachineCodeFile.Image image = MachineCodeFile.read(input);
            String source = disassemble(image.text, image.data);

            Assembler.Result rebuilt = Assembler.assemble(source);
            if (!Arrays.equals(image.text, rebuilt.text)
                    || !Arrays.equals(image.data, rebuilt.data)) {
                throw new IllegalArgumentException("Verification failed: reassembly changed bytes.");
            }
            Files.write(output, source.getBytes(StandardCharsets.UTF_8));
            System.out.println("Wrote " + output);
            System.out.println("Verified identical machine code: " + image.text.length
                    + " instruction bytes and " + image.data.length + " data bytes.");
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Disassembly failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Path defaultOutput(Path input) {
        String name = input.getFileName().toString();
        if (name.endsWith(".mhc-ascii")) name = name.substring(0, name.length() - 10);
        else if (name.endsWith(".mhc-bin")) name = name.substring(0, name.length() - 8);
        else {
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
        }
        return input.resolveSibling(name + ".disassembled.asm");
    }

    static String disassemble(byte[] text, byte[] data) {
        if (text.length == 0 || text.length % 4 != 0) {
            throw new IllegalArgumentException("The text section must contain whole 4-byte instructions.");
        }
        long end = (long) Assembler.TEXT_BASE + text.length;
        Set<Integer> targets = new TreeSet<>();
        for (int offset = 0; offset < text.length; offset += 4) {
            int address = Assembler.TEXT_BASE + offset;
            Integer target = controlTarget(wordAt(text, offset), address);
            if (target != null) {
                if (target < Assembler.TEXT_BASE || (long) target > end || (target & 3) != 0) {
                    throw new IllegalArgumentException("At 0x" + hex(address)
                            + ": branch/jump target 0x" + hex(target) + " is outside the text section.");
                }
                targets.add(target);
            }
        }

        StringBuilder source = new StringBuilder();
        source.append("# Reconstructed from machine code. Use delayed branching OFF in MARS.\n");
        source.append(".data 0x").append(hex(Assembler.DATA_BASE)).append('\n');
        for (int offset = 0; offset < data.length; offset += 16) {
            source.append("    .byte ");
            for (int i = offset; i < data.length && i < offset + 16; i++) {
                if (i != offset) source.append(", ");
                source.append(data[i] & 255);
            }
            source.append('\n');
        }
        source.append("\n.text 0x").append(hex(Assembler.TEXT_BASE)).append('\n');
        source.append(".globl main\nmain:\n");
        for (int offset = 0; offset < text.length; offset += 4) {
            int address = Assembler.TEXT_BASE + offset;
            if (offset != 0 && targets.contains(address)) {
                source.append(label(address)).append(":\n");
            }
            source.append("    ").append(decode(wordAt(text, offset), address)).append('\n');
        }
        if (targets.contains((int) end)) source.append(label((int) end)).append(":\n");
        return source.toString();
    }

    private static int wordAt(byte[] text, int offset) {
        return ((text[offset] & 255) << 24) | ((text[offset + 1] & 255) << 16)
                | ((text[offset + 2] & 255) << 8) | (text[offset + 3] & 255);
    }

    private static Integer controlTarget(int word, int address) {
        int opcode = word >>> 26;
        if (opcode == 4 || opcode == 5) return address + 4 + ((short) word) * 4;
        if (opcode == 2 || opcode == 3) {
            return ((address + 4) & 0xF0000000) | ((word & 0x03FFFFFF) << 2);
        }
        return null;
    }

    private static String decode(int word, int address) {
        int opcode = word >>> 26;
        int rs = (word >>> 21) & 31;
        int rt = (word >>> 16) & 31;
        int rd = (word >>> 11) & 31;
        int shift = (word >>> 6) & 31;
        int function = word & 63;
        int signedImmediate = (short) word;
        int unsignedImmediate = word & 65535;
        if (opcode == 0) {
            switch (function) {
                case 0:
                    require(rs == 0, address, word, "sll requires rs = 0");
                    return "sll " + reg(rd) + ", " + reg(rt) + ", " + shift;
                case 8:
                    require(rt == 0 && rd == 0 && shift == 0, address, word,
                            "jr has nonzero reserved fields");
                    return "jr " + reg(rs);
                case 12:
                    require(word == 12, address, word, "only syscall with code 0 is supported");
                    return "syscall";
                case 16:
                    require(rs == 0 && rt == 0 && shift == 0, address, word,
                            "mfhi has nonzero reserved fields");
                    return "mfhi " + reg(rd);
                case 26:
                    require(rd == 0 && shift == 0, address, word,
                            "div has nonzero reserved fields");
                    return "div " + reg(rs) + ", " + reg(rt);
                case 32:
                case 33:
                case 34:
                    require(shift == 0, address, word, "arithmetic instruction requires shamt = 0");
                    String op = function == 32 ? "add" : function == 33 ? "addu" : "sub";
                    return op + " " + reg(rd) + ", " + reg(rs) + ", " + reg(rt);
                default:
                    throw invalid(address, word, "unsupported R-type function " + function);
            }
        }
        switch (opcode) {
            case 2:
            case 3:
                return (opcode == 2 ? "j " : "jal ") + label(controlTarget(word, address));
            case 4:
            case 5:
                return (opcode == 4 ? "beq " : "bne ") + reg(rs) + ", " + reg(rt)
                        + ", " + label(controlTarget(word, address));
            case 8:
            case 9:
                return (opcode == 8 ? "addi " : "addiu ") + reg(rt) + ", " + reg(rs)
                        + ", " + signedImmediate;
            case 13:
                return "ori " + reg(rt) + ", " + reg(rs) + ", " + unsignedImmediate;
            case 15:
                require(rs == 0, address, word, "lui requires rs = 0");
                return "lui " + reg(rt) + ", " + unsignedImmediate;
            case 35:
            case 43:
                return (opcode == 35 ? "lw " : "sw ") + reg(rt) + ", " + signedImmediate
                        + "(" + reg(rs) + ")";
            default:
                throw invalid(address, word, "unsupported opcode " + opcode);
        }
    }

    private static String reg(int number) {
        return "$" + REGISTERS[number];
    }

    private static String label(int address) {
        return address == Assembler.TEXT_BASE ? "main" : "L" + hex(address);
    }

    private static String hex(int value) {
        return String.format(java.util.Locale.ROOT, "%08X", value);
    }

    private static void require(boolean condition, int address, int word, String message) {
        if (!condition) throw invalid(address, word, message);
    }

    private static IllegalArgumentException invalid(int address, int word, String message) {
        return new IllegalArgumentException("At 0x" + hex(address) + " (word 0x" + hex(word)
                + "): " + message + ".");
    }
}

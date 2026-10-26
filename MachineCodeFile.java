import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class MachineCodeFile {
    static final int TEXT_BASE = 0x00400000;
    static final int DATA_BASE = 0x10010000;
    static final int MAGIC = 0x4D484331; // ASCII MHC1
    static final int HEADER_BYTES = 20;

    static final class Image {
        final byte[] text;
        final byte[] data;

        Image(byte[] text, byte[] data) {
            this.text = text;
            this.data = data;
        }
    }

    private MachineCodeFile() { }

    static void writeAscii(Path path, byte[] text, byte[] data) throws IOException {
        validateSizes(text.length, data.length);
        StringBuilder out = new StringBuilder();
        out.append("# MHC1\n");
        out.append("# TEXT_BASE=0x00400000\n");
        out.append("# TEXT_BYTES=").append(text.length).append('\n');
        out.append("# DATA_BASE=0x10010000\n");
        out.append("# DATA_BYTES=").append(data.length).append('\n');
        out.append(".text\n");
        for (int i = 0; i < text.length; i += 4) {
            for (int j = 0; j < 4; j++) appendByte(out, text[i + j]);
            out.append('\n');
        }
        out.append(".data\n");
        for (byte value : data) {
            appendByte(out, value);
            out.append('\n');
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.US_ASCII));
    }

    static void writeBinary(Path path, byte[] text, byte[] data) throws IOException {
        validateSizes(text.length, data.length);
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
            out.writeInt(MAGIC);
            out.writeInt(TEXT_BASE);
            out.writeInt(text.length);
            out.writeInt(DATA_BASE);
            out.writeInt(data.length);
            out.write(text);
            out.write(data);
        }
    }

    static Image read(Path path) throws IOException {
        byte[] contents = Files.readAllBytes(path);
        if (contents.length >= 4 && contents[0] == 'M' && contents[1] == 'H'
                && contents[2] == 'C' && contents[3] == '1') {
            return readBinary(contents);
        }
        return readAscii(contents);
    }

    private static Image readBinary(byte[] contents) throws IOException {
        if (contents.length < HEADER_BYTES) fail("Truncated MHC1 binary header.");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(contents))) {
            if (in.readInt() != MAGIC) fail("Invalid MHC1 magic.");
            int textBase = in.readInt();
            int textSize = in.readInt();
            int dataBase = in.readInt();
            int dataSize = in.readInt();
            validateBases(textBase, dataBase);
            validateSizes(textSize, dataSize);
            if ((long) HEADER_BYTES + textSize + dataSize != contents.length) {
                fail("Binary length does not match the text/data sizes in its header.");
            }
            byte[] text = new byte[textSize];
            byte[] data = new byte[dataSize];
            in.readFully(text);
            in.readFully(data);
            return new Image(text, data);
        }
    }

    private static Image readAscii(byte[] contents) {
        for (byte b : contents) {
            if (b < 0) fail("Expected an ASCII MHC1 file or a binary file starting with MHC1.");
        }
        String[] raw = new String(contents, StandardCharsets.US_ASCII).split("\\r\\n|\\r|\\n");
        List<String> lines = new ArrayList<>();
        for (String line : raw) if (!line.trim().isEmpty()) lines.add(line.trim());
        if (lines.size() < 7 || !lines.get(0).equals("# MHC1")) {
            fail("Missing '# MHC1' ASCII header (or invalid binary magic).");
        }
        int textBase = metadata(lines.get(1), "TEXT_BASE", true);
        int textSize = metadata(lines.get(2), "TEXT_BYTES", false);
        int dataBase = metadata(lines.get(3), "DATA_BASE", true);
        int dataSize = metadata(lines.get(4), "DATA_BYTES", false);
        validateBases(textBase, dataBase);
        validateSizes(textSize, dataSize);
        long expectedLines = 7L + textSize / 4 + dataSize;
        if (expectedLines != lines.size()) {
            fail("ASCII row count does not match its declared text/data sizes.");
        }
        if (!lines.get(5).equals(".text")) fail("Expected .text after the ASCII header.");
        int dataMarker = 6 + textSize / 4;
        if (!lines.get(dataMarker).equals(".data")) fail("Expected .data after text instructions.");
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        for (int row = 6; row < dataMarker; row++) {
            String bits = lines.get(row);
            checkBits(bits, 32, row + 1);
            for (int bit = 0; bit < 32; bit += 8) {
                text.write(Integer.parseInt(bits.substring(bit, bit + 8), 2));
            }
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int row = dataMarker + 1; row < lines.size(); row++) {
            checkBits(lines.get(row), 8, row + 1);
            data.write(Integer.parseInt(lines.get(row), 2));
        }
        return new Image(text.toByteArray(), data.toByteArray());
    }

    private static int metadata(String line, String key, boolean address) {
        String prefix = "# " + key + "=";
        if (!line.startsWith(prefix)) fail("Expected metadata: " + prefix + "value");
        String value = line.substring(prefix.length());
        try {
            if (address) {
                if (!value.matches("0[xX][0-9A-Fa-f]{8}")) fail("Expected an 8-digit hex address for " + key);
                return (int) Long.parseLong(value.substring(2), 16);
            }
            if (!value.matches("[0-9]+")) fail("Expected a nonnegative byte count for " + key);
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid or oversized metadata value for " + key + ": " + value);
        }
    }

    private static void checkBits(String bits, int count, int row) {
        if (!bits.matches("[01]{" + count + "}")) {
            fail("ASCII row " + row + " must contain exactly " + count + " binary digits.");
        }
    }

    private static void appendByte(StringBuilder out, byte value) {
        for (int shift = 7; shift >= 0; shift--) out.append((value >>> shift) & 1);
    }

    private static void validateBases(int textBase, int dataBase) {
        if (textBase != TEXT_BASE || dataBase != DATA_BASE) {
            fail("This assembler uses .text=0x00400000 and .data=0x10010000.");
        }
    }

    private static void validateSizes(int textSize, int dataSize) {
        if (textSize <= 0 || textSize % 4 != 0) {
            fail("Text size must be a positive multiple of four bytes.");
        }
        if (dataSize < 0) fail("Data size cannot be negative.");
        if ((long) TEXT_BASE + textSize > DATA_BASE) fail("Text section overlaps the data address range.");
        if ((long) DATA_BASE + dataSize > 0x100000000L) fail("Data section exceeds the 32-bit address range.");
    }

    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }
}


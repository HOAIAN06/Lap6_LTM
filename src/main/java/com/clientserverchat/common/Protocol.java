package com.clientserverchat.common;

import java.io.*;
import java.util.List;

/** Length-delimited frames keep file bytes and commands from being confused. */
public final class Protocol {
    public static final int TCP_PORT = 2005;
    public static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
    public static final int MAX_FRAME_BYTES = MAX_FILE_BYTES + 128 * 1024;
    private static final int MAGIC = 0x43485433; // CHT3: authenticated login.

    public record Packet(String type, long id, List<String> fields, byte[] data) {
        public Packet(String type, long id, String... fields) {
            this(type, id, List.of(fields), new byte[0]);
        }
        public String field(int index) throws IOException {
            if (index >= fields.size()) throw new IOException("Thieu truong du lieu: " + type);
            return fields.get(index);
        }
    }

    public static byte[] encode(Packet packet) throws IOException {
        if (packet.data().length > MAX_FILE_BYTES || packet.fields().size() > 1024) {
            throw new IOException("Du lieu vuot gioi han");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeUTF(packet.type());
            out.writeLong(packet.id());
            out.writeInt(packet.fields().size());
            for (String field : packet.fields()) {
                out.writeUTF(field);
                if (bytes.size() > 128 * 1024) throw new IOException("Header qua lon");
            }
            out.writeInt(packet.data().length);
            out.write(packet.data());
        }
        if (bytes.size() > MAX_FRAME_BYTES) throw new IOException("Khung du lieu qua lon");
        return bytes.toByteArray();
    }

    public static Packet decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_FRAME_BYTES) throw new IOException("Khung du lieu qua lon");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("Sai phien ban giao thuc");
            String type = in.readUTF();
            long id = in.readLong();
            int count = in.readInt();
            if (count < 0 || count > 1024) throw new IOException("So truong khong hop le");
            String[] fields = new String[count];
            for (int i = 0; i < count; i++) fields[i] = in.readUTF();
            int length = in.readInt();
            if (length < 0 || length > MAX_FILE_BYTES || length != in.available()) {
                throw new IOException("Kich thuoc du lieu khong hop le");
            }
            return new Packet(type, id, List.of(fields), in.readNBytes(length));
        }
    }

    public static Packet read(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_FRAME_BYTES) throw new IOException("Kich thuoc khung khong hop le");
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        return decode(bytes);
    }

    public static void write(DataOutputStream out, Packet packet) throws IOException {
        byte[] bytes = encode(packet);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    public static String validName(String name) throws IOException {
        if (name == null || !name.matches("[\\p{L}\\p{N}_-]{1,32}")) {
            throw new IOException("Ten can 1-32 chu cai, chu so, _ hoac -");
        }
        return name;
    }

    public static String validFilename(String name) throws IOException {
        if (name == null || name.isBlank() || name.length() > 180
                || name.endsWith(".") || name.endsWith(" ")
                || name.chars().anyMatch(c -> Character.isISOControl(c) || "\\/:*?\"<>|".indexOf(c) >= 0)) {
            throw new IOException("Ten file khong hop le");
        }
        return name;
    }

    private Protocol() {}
}

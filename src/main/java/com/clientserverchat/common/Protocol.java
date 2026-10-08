/*
 * File: Protocol.java
 * Vai trò: Giao thức TCP dùng chung cho client và server.
 * Mục đích: Mã hóa/giải mã gói tin dạng frame có độ dài, tránh lẫn lệnh chat với byte file.
 * Phương thức chính:
 * - encode()/decode(): đổi Packet sang byte[] và ngược lại.
 * - read()/write(): đọc/ghi Packet qua DataInputStream/DataOutputStream.
 * - validName()/validFilename(): kiểm tra tên tài khoản và tên file.
 */
package com.clientserverchat.common;

import java.io.*;
import java.util.List;

/** Length-delimited frames keep file bytes and commands from being confused. */
public final class Protocol {
    public static final int TCP_PORT = 2005;
    public static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
    public static final int MAX_FRAME_BYTES = MAX_FILE_BYTES + 128 * 1024;
    private static final int MAGIC = 0x43485433; // CHT3: authenticated login.

    /** Gói dữ liệu TCP gồm loại lệnh, request id, danh sách chuỗi và dữ liệu file. */
    public record Packet(String type, long id, List<String> fields, byte[] data) {
        /** Tạo Packet không có dữ liệu file, chỉ có các trường chuỗi. */
        public Packet(String type, long id, String... fields) {
            this(type, id, List.of(fields), new byte[0]);
        }

        /** Lấy trường chuỗi theo vị trí và báo lỗi nếu client/server gửi thiếu dữ liệu. */
        public String field(int index) throws IOException {
            if (index >= fields.size()) throw new IOException("Thieu truong du lieu: " + type);
            return fields.get(index);
        }
    }

    /** Đóng gói Packet thành mảng byte theo định dạng frame nhị phân CHT3. */
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

    /** Giải mã mảng byte thành Packet và kiểm tra kích thước, magic number, số trường. */
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

    /** Đọc một frame đầy đủ từ TCP stream rồi giải mã thành Packet. */
    public static Packet read(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_FRAME_BYTES) throw new IOException("Kich thuoc khung khong hop le");
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        return decode(bytes);
    }

    /** Ghi một Packet xuống TCP stream kèm độ dài frame ở đầu. */
    public static void write(DataOutputStream out, Packet packet) throws IOException {
        byte[] bytes = encode(packet);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    /** Kiểm tra tên tài khoản chỉ gồm chữ/số Unicode, dấu _ hoặc dấu -. */
    public static String validName(String name) throws IOException {
        if (name == null || !name.matches("[\\p{L}\\p{N}_-]{1,32}")) {
            throw new IOException("Ten can 1-32 chu cai, chu so, _ hoac -");
        }
        return name;
    }

    /** Kiểm tra tên file an toàn, không chứa ký tự cấm của Windows và không quá dài. */
    public static String validFilename(String name) throws IOException {
        if (name == null || name.isBlank() || name.length() > 180
                || name.endsWith(".") || name.endsWith(" ")
                || name.chars().anyMatch(c -> Character.isISOControl(c) || "\\/:*?\"<>|".indexOf(c) >= 0)) {
            throw new IOException("Ten file khong hop le");
        }
        return name;
    }

    /** Không cho tạo object Protocol vì toàn bộ hàm là static helper. */
    private Protocol() {}
}

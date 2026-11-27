package com.pocketbridge;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.zip.CRC32;

/**
 * A ZIP writer with uncompressed (stored) entries whose total size is known before
 * the first byte is sent, so /api/zip can send Content-Length and the browser shows
 * real progress. Each entry's CRC goes in a data descriptor after its bytes (flag
 * bit 3), which lets the phone read every file once. Sizes are written in the
 * local header, the descriptor and the central directory.
 *
 * Only used when every size is known, nonzero and the archive fits without ZIP64;
 * otherwise the server falls back to streaming without a length.
 */
final class StoredZip {
    private static final long MAX_32 = 0xFFFFFFFFL;
    private static final int FLAGS = 0x0808; // bit 3: data descriptor, bit 11: UTF-8 names
    private static final int VERSION = 20;

    private StoredZip() { }

    /** The exact archive length for these entry names and sizes, or -1 when it needs ZIP64. */
    static long plannedLength(List<String> names, List<Long> sizes) {
        if (names.size() != sizes.size() || names.size() > 0xFFFF) return -1;
        long total = 22; // end of central directory
        for (int i = 0; i < names.size(); i++) {
            long size = sizes.get(i);
            if (size < 0 || size > MAX_32) return -1;
            int nameLength = names.get(i).getBytes(StandardCharsets.UTF_8).length;
            if (nameLength > 0xFFFF) return -1;
            total += 30 + nameLength + size + 16; // local header, data, descriptor
            total += 46 + nameLength;             // central directory record
        }
        return total > MAX_32 ? -1 : total;
    }

    static final class Writer {
        private final OutputStream out;
        private final int dosTime;
        private final int dosDate;
        private final List<byte[]> central = new ArrayList<>();
        private long offset;
        private EntryStream current;

        Writer(OutputStream out, long timeMillis) {
            this.out = out;
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(timeMillis);
            int year = Math.max(1980, calendar.get(Calendar.YEAR));
            dosTime = (calendar.get(Calendar.HOUR_OF_DAY) << 11) | (calendar.get(Calendar.MINUTE) << 5) | (calendar.get(Calendar.SECOND) >> 1);
            dosDate = ((year - 1980) << 9) | ((calendar.get(Calendar.MONTH) + 1) << 5) | calendar.get(Calendar.DAY_OF_MONTH);
        }

        /** Starts an entry that must contain exactly {@code size} bytes. */
        OutputStream begin(String name, long size) throws IOException {
            if (current != null) throw new IOException("Previous ZIP entry was not finished.");
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            Bytes header = new Bytes(30 + nameBytes.length);
            header.u32(0x04034b50).u16(VERSION).u16(FLAGS).u16(0).u16(dosTime).u16(dosDate)
                    .u32(0).u32(size).u32(size).u16(nameBytes.length).u16(0).raw(nameBytes);
            out.write(header.data);
            current = new EntryStream(out, nameBytes, size, offset);
            offset += header.data.length;
            return current;
        }

        /** Ends the entry; throws when the bytes written do not match the declared size. */
        void end() throws IOException {
            EntryStream entry = current;
            if (entry == null) throw new IOException("No ZIP entry is open.");
            current = null;
            if (entry.written != entry.size) {
                throw new IOException("A file changed size while zipping (" + entry.written + " of " + entry.size + " bytes).");
            }
            long crc = entry.crc.getValue();
            Bytes descriptor = new Bytes(16);
            descriptor.u32(0x08074b50).u32(crc).u32(entry.size).u32(entry.size);
            out.write(descriptor.data);
            offset += entry.size + 16;
            Bytes record = new Bytes(46 + entry.name.length);
            record.u32(0x02014b50).u16(VERSION).u16(VERSION).u16(FLAGS).u16(0).u16(dosTime).u16(dosDate)
                    .u32(crc).u32(entry.size).u32(entry.size).u16(entry.name.length).u16(0).u16(0)
                    .u16(0).u16(0).u32(0).u32(entry.headerOffset).raw(entry.name);
            central.add(record.data);
        }

        void finish() throws IOException {
            if (current != null) throw new IOException("Last ZIP entry was not finished.");
            long start = offset;
            long length = 0;
            for (byte[] record : central) { out.write(record); length += record.length; }
            Bytes end = new Bytes(22);
            end.u32(0x06054b50).u16(0).u16(0).u16(central.size()).u16(central.size())
                    .u32(length).u32(start).u16(0);
            out.write(end.data);
            out.flush();
        }
    }

    private static final class EntryStream extends FilterOutputStream {
        final byte[] name;
        final long size;
        final long headerOffset;
        final CRC32 crc = new CRC32();
        long written;

        EntryStream(OutputStream out, byte[] name, long size, long headerOffset) {
            super(out);
            this.name = name; this.size = size; this.headerOffset = headerOffset;
        }

        @Override public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (written + len > size) throw new IOException("A file grew while zipping; the download was stopped.");
            out.write(b, off, len);
            crc.update(b, off, len);
            written += len;
        }

        /** The archive owns the stream; finishing an entry must not close it. */
        @Override public void close() { }
    }

    private static final class Bytes {
        final byte[] data;
        int at;
        Bytes(int length) { data = new byte[length]; }
        Bytes u16(int v) { data[at++] = (byte) v; data[at++] = (byte) (v >> 8); return this; }
        Bytes u32(long v) { u16((int) (v & 0xFFFF)); return u16((int) ((v >>> 16) & 0xFFFF)); }
        Bytes raw(byte[] b) { System.arraycopy(b, 0, data, at, b.length); at += b.length; return this; }
    }
}

package direct.kabu.alertkun.mail.imap;

import java.io.IOException;
import java.io.InputStream;

/** Sequential raw MIME chunks. IMAP framing and literal handling remain owned by Angus. */
public final class ImapRawInputStream extends InputStream {
    public static final int CHUNK_BYTES = 65536;
    public static final int MAX_RAW_BYTES = 16777216;
    public interface Fetcher { Chunk fetch(long uid, int offset, int count) throws IOException; }
    public static final class Chunk {
        public final long uid;
        public final int offset, start, count;
        public final byte[] bytes;
        public Chunk(long uid, int offset, byte[] bytes, int start, int count) {
            this.uid = uid; this.offset = offset; this.bytes = bytes; this.start = start; this.count = count;
        }
    }
    public static final class ProtocolViolation extends IOException {
        public ProtocolViolation() { super("Invalid IMAP partial response"); }
    }
    public static final class RawLimitExceeded extends IOException {
        public RawLimitExceeded() { super("IMAP raw limit reached without EOF"); }
    }
    private final long uid, size;
    private final Fetcher fetcher;
    private final Runnable cancellation;
    private Chunk chunk;
    private int position, fetched;
    private boolean eof, closed;
    private IOException failure;

    public ImapRawInputStream(long uid, long size, Fetcher fetcher, Runnable cancellation) {
        if (uid <= 0 || size > MAX_RAW_BYTES) throw new IllegalArgumentException("Invalid raw stream metadata");
        this.uid = uid; this.size = size; this.fetcher = fetcher; this.cancellation = cancellation;
    }
    public int getFetchedBytes() { return fetched; }
    public IOException getFailure() { return failure; }
    private boolean availableChunk() throws IOException {
        cancellation.run();
        if (closed) throw new IOException("Closed raw stream");
        if (chunk != null && position < chunk.count) return true;
        chunk = null;
        if (eof) return false;
        try {
            if (fetched == MAX_RAW_BYTES) {
                if (size == fetched) { eof = true; return false; }
                throw new RawLimitExceeded();
            }
            int count = Math.min(CHUNK_BYTES, MAX_RAW_BYTES - fetched);
            Chunk next = fetcher.fetch(uid, fetched, count);
            cancellation.run();
            if (next == null || next.uid != uid || next.offset != fetched || next.count < 0 ||
                next.count > count || next.bytes == null || next.start < 0 ||
                next.start > next.bytes.length - next.count ||
                size >= 0 && ((long)fetched + next.count > size || next.count < count && fetched + next.count != size))
                throw new ProtocolViolation();
            fetched += next.count;
            eof = next.count < count;
            chunk = next; position = 0;
            return next.count != 0;
        } catch (IOException error) { failure = error; throw error; }
    }
    @Override public int read() throws IOException {
        return availableChunk() ? chunk.bytes[chunk.start + position++] & 255 : -1;
    }
    @Override public int read(byte[] bytes, int start, int count) throws IOException {
        if (start < 0 || count < 0 || start > bytes.length - count) throw new IndexOutOfBoundsException();
        if (count == 0) return 0;
        if (!availableChunk()) return -1;
        int amount = Math.min(count, chunk.count - position);
        System.arraycopy(chunk.bytes, chunk.start + position, bytes, start, amount);
        position += amount;
        return amount;
    }
    @Override public void close() { closed = true; chunk = null; }
}

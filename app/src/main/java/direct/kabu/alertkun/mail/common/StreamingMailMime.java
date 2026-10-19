package direct.kabu.alertkun.mail.common;

import direct.kabu.alertkun.mail.common.BoundedMailText;
import java.io.*;
import java.util.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.internet.ContentDisposition;
import jakarta.mail.internet.ContentType;
import org.apache.james.mime4j.MimeException;
import org.apache.james.mime4j.stream.*;

/** Shared POP3/IMAP raw-MIME parsing. No DOM or message-size-proportional heap buffers. */
public final class StreamingMailMime {
    public static final long MAX_RAW_BYTES = 16777216L;
    public static final class Result {
        public boolean unsupported;
        public String subject, from, body = "";
        public final Map<String, String> headers = new LinkedHashMap<>();
    }
    private static final class Frame {
        final BoundedMailText body = new BoundedMailText();
        boolean attachment, alternative, plain, selected, candidate;
    }
    public static Result parse(InputStream source) throws IOException {
        Result result = new Result();
        MimeTokenStream parser = new MimeTokenStream(MimeConfig.custom().setStrictParsing(true)
            .setMaxLineLen(8192).setMaxHeaderCount(100).setMaxHeaderLen(65536)
            .setMaxTotalHeaderCount(1024).setMaxTotalHeaderLen(262144)
            .setMaxNestingDepth(16).setMaxPartCount(256).setMaxContentLen(MAX_RAW_BYTES).build());
        parser.setRecursionMode(RecursionMode.M_NO_RECURSE);
        Deque<Frame> frames = new ArrayDeque<>();
        frames.push(new Frame());
        int depth = 0, parts = 0;
        try {
            parser.parse(source);
            for (EntityState state = parser.getState(); state != EntityState.T_END_OF_STREAM; state = parser.next()) {
                Frame frame = frames.peek();
                switch (state) {
                    case T_START_BODYPART:
                        if (++parts > 256) throw new MimeException("Part limit");
                        Frame child = new Frame();
                        child.attachment = frame.attachment;
                        frames.push(child);
                        break;
                    case T_START_MULTIPART:
                        if (++depth > 16) throw new MimeException("Depth limit");
                        frame.alternative = parser.getBodyDescriptor().getMimeType().equalsIgnoreCase("multipart/alternative");
                        break;
                    case T_END_MULTIPART: depth--; break;
                    case T_END_BODYPART:
                        Frame complete = frames.pop();
                        Frame parent = frames.peek();
                        if (!complete.attachment && complete.candidate) {
                            if (!parent.alternative) parent.body.append(complete.body.value());
                            else if (!parent.selected || complete.plain && !parent.plain) {
                                parent.body.clear();
                                parent.body.append(complete.body.value());
                                parent.selected = true;
                            }
                            parent.plain |= complete.plain;
                            parent.candidate = true;
                        }
                        break;
                    case T_FIELD:
                        Field field = parser.getField();
                        String name = field.getName(), value = field.getBody();
                        if (name.equalsIgnoreCase("Content-Disposition")) {
                            ContentDisposition disposition = new ContentDisposition(value);
                            if ("attachment".equalsIgnoreCase(disposition.getDisposition()) ||
                                    disposition.getParameter("filename") != null) frame.attachment = true;
                        }
                        if (name.equalsIgnoreCase("Content-Type") && new ContentType(value).getParameter("name") != null)
                            frame.attachment = true;
                        if (frames.size() == 1) {
                            if (name.equalsIgnoreCase("Subject")) result.subject = MimeUtility.decodeText(value);
                            if (name.equalsIgnoreCase("From")) {
                                StringJoiner addresses = new StringJoiner(", ");
                                for (InternetAddress address : InternetAddress.parseHeader(value, false)) addresses.add(address.toUnicodeString());
                                result.from = addresses.toString();
                            }
                            if (name.equalsIgnoreCase("Message-ID") || name.equalsIgnoreCase("Date")) result.headers.put(name, value);
                        }
                        break;
                    case T_PREAMBLE:
                    case T_EPILOGUE:
                        discardEdge(parser.getInputStream());
                        break;
                    case T_BODY:
                        String type = parser.getBodyDescriptor().getMimeType();
                        if (!frame.attachment && (type.equalsIgnoreCase("text/plain") || type.equalsIgnoreCase("text/html"))) {
                            frame.candidate = true;
                            frame.plain = type.equalsIgnoreCase("text/plain");
                            frame.body.read(parser.getReader(), !frame.plain);
                        }
                        // A root text body can finish its token sequence without consuming the body stream.
                        // Drain explicitly: later alternatives and transport EOF checks must still run.
                        discardBody(parser.getInputStream());
                        break;
                    default: break;
                }
            }
            discardBody(source);
            result.body = frames.peek().body.value();
        } catch (MimeException | jakarta.mail.internet.ParseException | UnsupportedEncodingException malformed) {
            result.unsupported = true;
        } catch (IOException failure) {
            if (!failure.getClass().getName().startsWith("org.apache.james.mime4j.")) throw failure;
            result.unsupported = true;
        }
        if (result.unsupported) { result.body = ""; result.subject = null; result.from = null; result.headers.clear(); }
        return result;
    }
    private static void discardEdge(InputStream input) throws IOException, MimeException {
        byte[] buffer = new byte[1024];
        int total = 0, count;
        while ((count = input.read(buffer)) >= 0) {
            total += count;
            if (total > 65536) throw new MimeException("Preamble/epilogue limit");
        }
    }
    private static void discardBody(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        while (input.read(buffer) >= 0) { /* No content retained. */ }
    }
}

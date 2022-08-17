
// adapted from https://github.com/fusesource/jansi/blob/70ff98d5cbd5fb005d8a44ed31050388b256f9c6/jansi/src/main/java/org/fusesource/jansi/HtmlAnsiOutputStream.java

package almond.internals;

import org.fusesource.jansi.io.AnsiOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class HtmlAnsiOutputStream extends AnsiOutputStream {

    private boolean concealOn = false;

    @Override
    public void close() throws IOException {
        closeAttributes();
        super.close();
    }

    private static final String[] ANSI_COLOR_MAP = {"black", "red",
            "green", "yellow", "blue", "magenta", "cyan", "white",};
    private static final String[] RGB_COLOR_MAP = {"black", "red",
            "rgb(0, 187, 0)", "yellow", "blue", "magenta", "rgb(0, 187, 187)", "white",};

    private static final byte[] BYTES_QUOT = "&quot;".getBytes();
    private static final byte[] BYTES_AMP = "&amp;".getBytes();
    private static final byte[] BYTES_LT = "&lt;".getBytes();
    private static final byte[] BYTES_GT = "&gt;".getBytes();

    public HtmlAnsiOutputStream(OutputStream os) {
        super(os);
    }

    private final List<String> closingAttributes = new ArrayList<String>();

    private void write(String s) throws IOException {
        super.out.write(s.getBytes());
    }

    private void writeAttribute(String s) throws IOException {
        write("<" + s + ">");
        closingAttributes.add(0, s.split(" ", 2)[0]);
    }

    private void closeAttributes() throws IOException {
        for (String attr : closingAttributes) {
            write("</" + attr + ">");
        }
        closingAttributes.clear();
    }

    public void write(int data) throws IOException {
        switch (data) {
            case 34: // "
                out.write(BYTES_QUOT);
                break;
            case 38: // &
                out.write(BYTES_AMP);
                break;
            case 60: // <
                out.write(BYTES_LT);
                break;
            case 62: // >
                out.write(BYTES_GT);
                break;
            default:
                super.write(data);
        }
    }

    public void writeLine(byte[] buf, int offset, int len) throws IOException {
        write(buf, offset, len);
        closeAttributes();
    }

    @Override
    protected void processSetAttribute(int attribute) throws IOException {
        switch (attribute) {
            case ATTRIBUTE_CONCEAL_ON:
                write("\u001B[8m");
                concealOn = true;
                break;
            case ATTRIBUTE_INTENSITY_BOLD:
                writeAttribute("b");
                break;
            case ATTRIBUTE_INTENSITY_NORMAL:
                closeAttributes();
                break;
            case ATTRIBUTE_UNDERLINE:
                writeAttribute("u");
                break;
            case ATTRIBUTE_UNDERLINE_OFF:
                closeAttributes();
                break;
            case ATTRIBUTE_NEGATIVE_ON:
                break;
            case ATTRIBUTE_NEGATIVE_OFF:
                break;
            default:
                break;
        }
    }

    @Override
    protected void processAttributeRest() throws IOException {
        if (concealOn) {
            write("\u001B[0m");
            concealOn = false;
        }
        closeAttributes();
    }

    @Override
    protected void processDefaultTextColor() throws IOException {
        processAttributeRest();
    }

    @Override
    protected void processSetForegroundColor(int color, boolean bright) throws IOException {
        // hard-coded color are for nteract (where the ansi-* classes are defined), and it might be useful from nbviewer too
        // ansi-* classes are for jupyterlab (and classic too I think)
        writeAttribute("span style=\"color: " + RGB_COLOR_MAP[color] + "\"");
        writeAttribute("span class=\"ansi-" + ANSI_COLOR_MAP[color] + "-fg\"");
    }

    @Override
    protected void processSetBackgroundColor(int color, boolean bright) throws IOException {
        String extra = "";
        if (color == 7)
            extra = "; color: rgb(255, 255, 255);";
        writeAttribute("span style=\"background-color: " + RGB_COLOR_MAP[color] + extra + "\"");
        writeAttribute("span class=\"ansi-" + ANSI_COLOR_MAP[color] + "-bg\"");
    }
}

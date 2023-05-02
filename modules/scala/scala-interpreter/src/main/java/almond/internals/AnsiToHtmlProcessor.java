// adapted from https://github.com/Osiris-Team/jansi/blob/3a832dfc0c4bd9d00de356dbac0f0fbaebf75786/src/main/java/org/fusesource/jansi/io/AnsiToHtmlProcessor.java
// see: https://github.com/fusesource/jansi/pull/212

package almond.internals;

import org.fusesource.jansi.io.AnsiProcessor;

import java.io.IOException;
import java.io.OutputStream;

public class AnsiToHtmlProcessor extends AnsiProcessor {
    private boolean concealOn = false;
    private HtmlAnsiOutputStream haos;

    AnsiToHtmlProcessor(OutputStream os) {
        super(os);
    }

    public HtmlAnsiOutputStream getHtmlAnsiOutputStream() {
        return haos;
    }

    public void setHtmlAnsiOutputStream(HtmlAnsiOutputStream haos) {
        this.haos = haos;
    }

    @Override
    protected void processSetAttribute(int attribute) throws IOException {
        switch (attribute) {
            case ATTRIBUTE_CONCEAL_ON:
                haos.write("\u001B[8m");
                concealOn = true;
                break;
            case ATTRIBUTE_INTENSITY_BOLD:
                haos.writeAttribute("b");
                break;
            case ATTRIBUTE_INTENSITY_NORMAL:
                haos.closeAttributes();
                break;
            case ATTRIBUTE_UNDERLINE:
                haos.writeAttribute("u");
                break;
            case ATTRIBUTE_UNDERLINE_OFF:
                haos.closeAttributes();
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
    protected void processDefaultTextColor() throws IOException {
        processAttributeReset();
    }

    @Override
    protected void processAttributeReset() throws IOException {
        if (concealOn) {
            haos.write("\u001B[0m");
            concealOn = false;
        }
        haos.closeAttributes();
    }

    @Override
    protected void processSetForegroundColor(int color, boolean bright) throws IOException {
        // hard-coded color are for nteract (where the ansi-* classes are defined), and it might be useful from nbviewer too
        // ansi-* classes are for jupyterlab (and classic too I think)
        haos.writeAttribute("span style=\"color: " + HtmlAnsiOutputStream.RGB_COLOR_MAP[color] + "\"");
        haos.writeAttribute("span class=\"ansi-" + HtmlAnsiOutputStream.ANSI_COLOR_MAP[color] + "-fg\"");
    }

    @Override
    protected void processSetBackgroundColor(int color, boolean bright) throws IOException {
        String extra = "";
        if (color == 7)
            extra = "; color: rgb(255, 255, 255);";
        haos.writeAttribute("span style=\"background-color: " + HtmlAnsiOutputStream.RGB_COLOR_MAP[color] + extra + "\"");
        haos.writeAttribute("span class=\"ansi-" + HtmlAnsiOutputStream.ANSI_COLOR_MAP[color] + "-bg\"");
    }
}

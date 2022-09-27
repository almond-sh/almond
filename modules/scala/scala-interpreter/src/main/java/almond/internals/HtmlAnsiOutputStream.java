// adapted from https://github.com/Osiris-Team/jansi/blob/3a832dfc0c4bd9d00de356dbac0f0fbaebf75786/src/main/java/org/fusesource/jansi/io/HtmlAnsiOutputStream.java
// see: https://github.com/fusesource/jansi/pull/212

package almond.internals;

import org.fusesource.jansi.io.AnsiOutputStream;

/*
 * Copyright (C) 2009-2017 the original author(s).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.fusesource.jansi.AnsiColors;
import org.fusesource.jansi.AnsiMode;
import org.fusesource.jansi.AnsiType;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A optimized/edited {@link HtmlAnsiOutputStream} for ansi 2.x and above. <br>
 * @author <a href="https://github.com/Osiris-Team">Osiris Team</a>
 * @author <a href="http://code.dblock.org">Daniel Doubrovkine</a>
 */
public class HtmlAnsiOutputStream extends AnsiOutputStream {

    private static final Map<OutputStream, AnsiToHtmlProcessor> streamsAndProcessors = new ConcurrentHashMap<>();

    /**
     * Creates a new {@link AnsiToHtmlProcessor} for the provided {@link OutputStream}, <br>
     * and adds both to the {@link #streamsAndProcessors} map. <br>
     * This is done to have working multithreading and diminish the need of a getProcessor() method <br>
     * in the {@link AnsiOutputStream}.
     */
    private static synchronized AnsiToHtmlProcessor createAnsiToHtmlProcessorForOutput(OutputStream out){
        AnsiToHtmlProcessor processor = new AnsiToHtmlProcessor(out);
        streamsAndProcessors.put(out, processor);
        return processor;
    }

    @Override
    public void close() throws IOException {
        closeAttributes();
        super.close();
    }

    public static final String[] ANSI_COLOR_MAP = {"black", "red",
            "green", "yellow", "blue", "magenta", "cyan", "white",};
    public static final String[] RGB_COLOR_MAP = {"black", "red",
            "rgb(0, 187, 0)", "yellow", "blue", "magenta", "rgb(0, 187, 187)", "white",};

    private static final byte[] BYTES_QUOT = "&quot;".getBytes();
    private static final byte[] BYTES_AMP = "&amp;".getBytes();
    private static final byte[] BYTES_LT = "&lt;".getBytes();
    private static final byte[] BYTES_GT = "&gt;".getBytes();

    public HtmlAnsiOutputStream(OutputStream os) {
        super(os,
                new WidthSupplier() {
                    @Override
                    public int getTerminalWidth() {
                        return Integer.MAX_VALUE;
                    }
                },
                AnsiMode.Default,
                createAnsiToHtmlProcessorForOutput(os),
                AnsiType.Native,
                AnsiColors.Colors16,
                Charset.defaultCharset(),
                null,
                null,
                true);
        streamsAndProcessors.get(os).setHtmlAnsiOutputStream(this);
    }

    private final List<String> closingAttributes = new ArrayList<>();

    public void write(String s) throws IOException {
        super.out.write(s.getBytes());
    }

    public void writeAttribute(String s) throws IOException {
        write("<" + s + ">");
        closingAttributes.add(0, s.split(" ", 2)[0]);
    }

    public void closeAttributes() throws IOException {
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
}

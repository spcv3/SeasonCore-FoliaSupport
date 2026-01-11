package Kinkin.aeternum.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Construye libros sin que se “corte” el texto:
 * - Envuelve el texto por palabras
 * - Ignora los códigos de color §x al contar ancho
 * - Crea páginas nuevas al pasar el límite de líneas
 */
public class BookPaginator {

    // Ajusta estos valores probando en juego
    private static final int MAX_LINES_PER_PAGE = 14;
    private static final int MAX_VISIBLE_CHARS_PER_LINE = 20;

    private final List<String> pages = new ArrayList<>();
    private final List<String> currentLines = new ArrayList<>();

    private StringBuilder currentLine = new StringBuilder();
    private int currentVisibleLen = 0;

    /** Añade un bloque de texto que puede tener \n dentro (párrafos, listas, etc.). */
    public void addText(String text) {
        if (text == null) return;

        String[] paragraphs = text.split("\n", -1);
        for (int i = 0; i < paragraphs.length; i++) {
            addParagraph(paragraphs[i]);
            if (i < paragraphs.length - 1) {
                newLine(); // respetar saltos de línea explícitos
            }
        }
    }

    /** Añade una línea “lógica” (se envuelve si es muy larga). */
    public void addLine(String line) {
        addParagraph(line);
        newLine();
    }

    /** Línea en blanco. */
    public void addBlankLine() {
        newLine();
    }

    public void newPage() {
        closeLine();
        closePage();
    }

    /** Finaliza y devuelve todas las páginas generadas. */
    public List<String> build() {
        closeLine();
        closePage();
        return new ArrayList<>(pages);
    }

    // ---------------- internos ----------------

    private void addParagraph(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        String[] words = text.split(" ");
        for (String word : words) {
            int wordLen = visibleLength(word);

            int extra = (currentVisibleLen == 0 ? 0 : 1); // espacio
            if (currentVisibleLen + extra + wordLen > MAX_VISIBLE_CHARS_PER_LINE) {
                newLine();
            }

            if (currentLine.length() > 0) {
                currentLine.append(" ");
                currentVisibleLen += 1;
            }

            currentLine.append(word);
            currentVisibleLen += wordLen;
        }
    }

    private void newLine() {
        closeLine();
        if (currentLines.size() >= MAX_LINES_PER_PAGE) {
            closePage();
        }
    }

    private void closeLine() {
        if (currentLine.length() > 0) {
            currentLines.add(currentLine.toString());
            currentLine = new StringBuilder();
            currentVisibleLen = 0;

            if (currentLines.size() >= MAX_LINES_PER_PAGE) {
                closePage();
            }
        }
    }

    private void closePage() {
        if (!currentLines.isEmpty()) {
            pages.add(String.join("\n", currentLines));
            currentLines.clear();
        }
    }

    /** Cuenta caracteres visibles (ignora códigos de color §x). */
    private int visibleLength(String s) {
        int len = 0;
        boolean skipNext = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (skipNext) {
                skipNext = false;
                continue;
            }
            if (c == '§' && i + 1 < s.length()) {
                skipNext = true; // saltar código de color
                continue;
            }
            len++;
        }
        return len;
    }
}

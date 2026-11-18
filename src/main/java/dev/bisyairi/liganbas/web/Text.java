package dev.bisyairi.liganbas.web;

public final class Text {
    private Text() {
    }

    /** "BAYU PUTERI APARTMENT" -> "Bayu Puteri Apartment". */
    public static String title(String s) {
        StringBuilder out = new StringBuilder();
        for (String w : s.toLowerCase().split(" ")) {
            if (!w.isEmpty()) {
                out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            }
        }
        return out.toString().trim();
    }
}

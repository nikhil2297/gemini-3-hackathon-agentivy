package com.agentivy.backend.tools.angular;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AngularLogParser {

    private static final Pattern TS_ERROR = Pattern.compile("error (TS\\d+):\\s*(.+?)(?=\\n|$)", Pattern.MULTILINE);
    private static final Pattern FILE_ERROR = Pattern.compile("(src/[^:]+):(\\d+):(\\d+)\\s*-\\s*error\\s+(TS\\d+):\\s*(.+?)(?=\\n|$)", Pattern.MULTILINE);
    private static final Pattern GENERIC_ERROR = Pattern.compile("Error:\\s*(.+?)(?=\\n|$)", Pattern.MULTILINE);

    public boolean hasCompilationErrors(String output) {
        return output.contains("error TS") ||
                output.contains("ERROR in") ||
                output.contains("✖ Failed to compile") ||
                (output.contains("Build at:") && output.contains("error"));
    }

    public boolean isCompilationComplete(String output) {
        return output.contains("Compiled successfully") ||
                output.contains("Angular Live Development Server is listening");
    }

    public List<String> extractErrors(String output) {
        List<String> errors = new ArrayList<>();
        extract(output, TS_ERROR, errors);
        extract(output, FILE_ERROR, errors);
        extract(output, GENERIC_ERROR, errors);
        return errors;
    }

    private void extract(String input, Pattern pattern, List<String> collector) {
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) {
            String match = matcher.group(0).trim(); // Capture full match context
            if (!collector.contains(match)) {
                collector.add(match);
            }
        }
    }
}
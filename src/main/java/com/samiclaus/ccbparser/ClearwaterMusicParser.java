package com.samiclaus.ccbparser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ClearwaterMusicParser {
    private static final String PAGE_URL_PREFIX =
            "https://www.clearwatercommunityband.com/copy-of-music-template-";
    private static final int MAX_PAGE = 999;
    private static final int CONSECUTIVE_404_LIMIT = 3;
    private static final long REQUEST_DELAY_MILLIS = 3_000;
    private static final int MAX_429_RETRIES = 5;
    private static final long INITIAL_429_BACKOFF_MILLIS = 30_000;
    private static final long MAX_429_BACKOFF_MILLIS = 5 * 60_000;
    private static final Path OUTPUT_FILE = Path.of("clearwater-music.txt");

    private ClearwaterMusicParser() {
    }

    public static void main(String[] args) throws IOException {
        int maxPage = readPositiveIntProperty("ccb.maxPage", MAX_PAGE);
        int missingLimit = readPositiveIntProperty("ccb.consecutive404Limit", CONSECUTIVE_404_LIMIT);
        long requestDelay = readNonNegativeLongProperty("ccb.requestDelayMillis", REQUEST_DELAY_MILLIS);
        Path output = Path.of(System.getProperty("ccb.output", OUTPUT_FILE.toString()));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        List<PageResult> results = scan(client, maxPage, missingLimit, requestDelay);
        String report = formatReport(results);
        Files.writeString(output, report, StandardCharsets.UTF_8);

        System.out.println(report);
        System.out.println();
        System.out.println("Wrote " + results.stream().mapToInt(r -> r.titles().size()).sum()
                + " title(s) to " + output.toAbsolutePath());
    }

    private static List<PageResult> scan(
            HttpClient client,
            int maxPage,
            int missingLimit,
            long requestDelayMillis) {

        List<PageResult> accepted = new ArrayList<>();
        int consecutive404s = 0;

        // Deliberately send requests one at a time. Parallel requests caused
        // the website to rate-limit the parser with HTTP 429 responses.
        for (int page = 1; page <= maxPage && consecutive404s < missingLimit; page++) {
            if (page > 1 && !sleep(requestDelayMillis)) {
                accepted.add(new PageResult(page, "ERROR", List.of(),
                        "Request delay interrupted; stopping scan."));
                break;
            }

            PageResult result = fetchPage(client, page);

            if ("404".equals(result.status())) {
                consecutive404s++;
                continue;
            }

            consecutive404s = 0;
            accepted.add(result);

            if ("ERROR".equals(result.status()) && result.detail().contains("interrupted")) {
                break;
            }
        }

        return accepted;
    }

    private static PageResult fetchPage(HttpClient client, int pageNumber) {
        String url = PAGE_URL_PREFIX + pageNumber;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (compatible; ClearwaterMusicParser/1.0)")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build();

        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<String> response = client.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int statusCode = response.statusCode();

                if (statusCode == 429) {
                    if (attempt >= MAX_429_RETRIES) {
                        return new PageResult(pageNumber, "HTTP 429", List.of(),
                                url + " — rate limit persisted after " + MAX_429_RETRIES + " retries");
                    }

                    long waitMillis = get429WaitMillis(response, attempt);
                    System.out.println("HTTP 429 on page " + pageNumber + ". Waiting "
                            + (waitMillis / 1_000) + " seconds before retry "
                            + (attempt + 1) + "/" + MAX_429_RETRIES + ".");
                    if (!sleep(waitMillis)) {
                        return new PageResult(pageNumber, "ERROR", List.of(),
                                "Request interrupted while waiting after HTTP 429.");
                    }
                    continue;
                }

                if (statusCode == 404) {
                    return new PageResult(pageNumber, "404", List.of(), url);
                }

                if (statusCode < 200 || statusCode >= 300) {
                    return new PageResult(pageNumber, "HTTP " + statusCode, List.of(), url);
                }

                Document document = Jsoup.parse(response.body(), url);
                Set<String> uniqueTitles = new LinkedHashSet<>();
                Elements headings = document.select("h3, h4");

                for (Element heading : headings) {
                    String title = heading.text().replaceAll("\\s+", " ").trim();
                    if (isLikelyMusicTitle(title)) {
                        uniqueTitles.add(title);
                    }
                }

                return new PageResult(pageNumber, "OK", List.copyOf(uniqueTitles), url);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return new PageResult(pageNumber, "ERROR", List.of(),
                        "Request interrupted: " + exception.getMessage());
            } catch (IOException | IllegalArgumentException exception) {
                return new PageResult(pageNumber, "ERROR", List.of(),
                        url + " — " + exception.getMessage());
            }
        }
    }

    private static long get429WaitMillis(HttpResponse<?> response, int attempt) {
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        if (retryAfter != null) {
            try {
                return Math.max(1_000, Long.parseLong(retryAfter.trim()) * 1_000);
            } catch (NumberFormatException ignored) {
                try {
                    Instant retryAt = ZonedDateTime.parse(
                            retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                    return Math.max(1_000, Duration.between(Instant.now(), retryAt).toMillis());
                } catch (DateTimeParseException ignoredDate) {
                    // Use exponential backoff if the header is missing or invalid.
                }
            }
        }

        long multiplier = 1L << Math.min(attempt, 10);
        return Math.min(INITIAL_429_BACKOFF_MILLIS * multiplier, MAX_429_BACKOFF_MILLIS);
    }

    private static boolean sleep(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isLikelyMusicTitle(String title) {
        if (title.isBlank() || title.length() > 180) {
            return false;
        }

        String normalized = title.toLowerCase();
        if (normalized.equals("home") || normalized.equals("about") || normalized.equals("contact")
                || normalized.equals("menu") || normalized.equals("music") || normalized.equals("events")
                || normalized.equals("the band") || normalized.equals("join us")) {
            return false;
        }

        return true;
    }

    private static String formatReport(List<PageResult> results) {
        StringBuilder output = new StringBuilder();
        output.append("Clearwater Community Band — music page headings").append(System.lineSeparator());
        output.append("Source: ").append(PAGE_URL_PREFIX).append("{page}").append(System.lineSeparator());
        output.append("===============================================").append(System.lineSeparator());

        int number = 1;
        for (PageResult result : results) {
            if (!"OK".equals(result.status())) {
                output.append("[Page ").append(result.page()).append("] ")
                        .append(result.status()).append(": ").append(result.detail())
                        .append(System.lineSeparator());
                continue;
            }

            for (String title : result.titles()) {
                output.append(number++).append(". ").append(title)
                        .append(" (page ").append(result.page()).append(")")
                        .append(System.lineSeparator());
            }
        }

        if (number == 1) {
            output.append("No music headings were found. Check network access and the site's current page structure.")
                    .append(System.lineSeparator());
        }

        return output.toString();
    }

    private static int readPositiveIntProperty(String property, int defaultValue) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Fall back to the default value below.
        }

        System.err.println("Ignoring invalid value for " + property + ": " + value);
        return defaultValue;
    }

    private static long readNonNegativeLongProperty(String property, long defaultValue) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            long parsed = Long.parseLong(value);
            if (parsed >= 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Fall back to the default value below.
        }

        System.err.println("Ignoring invalid value for " + property + ": " + value);
        return defaultValue;
    }

    private record PageResult(int page, String status, List<String> titles, String detail) {
    }
}

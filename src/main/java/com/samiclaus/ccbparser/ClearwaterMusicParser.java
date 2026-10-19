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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ClearwaterMusicParser {
    private static final String PAGE_URL_PREFIX =
            "https://www.clearwatercommunityband.com/copy-of-music-template-";
    private static final int MAX_PAGE = 999;
    private static final int CONCURRENCY = 10;
    private static final int CONSECUTIVE_404_LIMIT = 3;
    private static final Path OUTPUT_FILE = Path.of("clearwater-music.txt");

    private ClearwaterMusicParser() {
    }

    public static void main(String[] args) throws IOException {
        int maxPage = readPositiveIntProperty("ccb.maxPage", MAX_PAGE);
        int concurrency = readPositiveIntProperty("ccb.concurrency", CONCURRENCY);
        int missingLimit = readPositiveIntProperty("ccb.consecutive404Limit", CONSECUTIVE_404_LIMIT);
        Path output = Path.of(System.getProperty("ccb.output", OUTPUT_FILE.toString()));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        List<PageResult> results = scan(client, maxPage, concurrency, missingLimit);
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
            int concurrency,
            int missingLimit) {

        List<PageResult> accepted = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        int consecutive404s = 0;

        try {
            for (int first = 1; first <= maxPage && consecutive404s < missingLimit; first += concurrency) {
                int last = Math.min(maxPage, first + concurrency - 1);
                List<Future<PageResult>> futures = new ArrayList<>();

                for (int page = first; page <= last; page++) {
                    final int pageNumber = page;
                    futures.add(executor.submit(() -> fetchPage(client, pageNumber)));
                }

                for (Future<PageResult> future : futures) {
                    PageResult result;
                    try {
                        result = future.get();
                    } catch (Exception exception) {
                        result = new PageResult(-1, "ERROR", List.of(),
                                "Unexpected fetch failure: " + exception.getMessage());
                    }

                    if (result.page() == -1) {
                        accepted.add(result);
                        continue;
                    }

                    if ("404".equals(result.status())) {
                        consecutive404s++;
                        if (consecutive404s >= missingLimit) {
                            break;
                        }
                    } else {
                        consecutive404s = 0;
                        accepted.add(result);
                    }
                }
            }
        } finally {
            executor.shutdownNow();
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

        try {
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int statusCode = response.statusCode();

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

    private record PageResult(int page, String status, List<String> titles, String detail) {
    }
}

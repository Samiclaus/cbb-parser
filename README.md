# Clearwater Community Band Parser

A small Java 17 / Maven application that scans the numbered music-template pages on the
[Clearwater Community Band website](https://www.clearwatercommunityband.com/copy-of-music-template)
and writes the extracted `h3` and `h4` headings to `clearwater-music.txt`.

## Build and run

Requirements: JDK 17+ and Maven 3.8+.

```bash
mvn clean package
```

The Maven `package` phase runs the parser and writes `clearwater-music.txt` into the
project's current directory. The same results are also printed to the console.

## Parser behavior

- Starts at `copy-of-music-template-1`, then checks numbered pages in ascending order.
- Fetches up to 10 pages concurrently.
- Stops after three consecutive HTTP 404 responses.
- Extracts distinct `h3` and `h4` headings per page.
- Logs non-200 responses and request errors to the output file.

The website may change its page structure, so the output should be reviewed if the site is updated.

## Optional settings

These Java system properties can be supplied to Maven's exec plugin:

- `ccb.maxPage`: maximum page number (default `999`)
- `ccb.concurrency`: number of concurrent requests (default `10`)
- `ccb.consecutive404Limit`: consecutive 404s before stopping (default `3`)
- `ccb.output`: output path (default `clearwater-music.txt`)

For example:

```bash
mvn -Dccb.maxPage=100 -Dccb.concurrency=5 clean package
```

package com.example;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.client.methods.CloseableHttpResponse;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.regex.Pattern;

public class PDFScraper {

    // Constants for threading, retries, and timeout
    private static final int THREAD_COUNT = 50;
    private static final int MAX_RETRIES = 3;
    private static final int TIMEOUT = 60000; // milliseconds

    // URL and directory constants
    private static final String BASE_URL = "https://papers.nips.cc";
    // (Datasets URL remains defined if needed)
    private static final String DATASETS_BENCHMARKS_URL_2021 = "https://datasets-benchmarks-proceedings.neurips.cc";
    private static final String OUTPUT_DIR = "D:/scraped-pdfs/";      // PDFs (if downloaded) are saved here
    private static final String METADATA_DIR = "D:/metadata/";           // Individual metadata JSON files go here

    // Incremental metadata output files
    private static final String JSON_OUTPUT = "output.json";
    private static final String CSV_OUTPUT = "output.csv";

    // Year range
    private static final int START_YEAR = 1987;
    private static final int END_YEAR = 2023;

    // For thread-safe appending to incremental files
    private static final Object jsonLock = new Object();
    private static final Object csvLock = new Object();

    // Utility: sanitize a filename (remove invalid characters)
    private static String sanitizeFilename(String filename) {
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // Download PDF file (if desired). (PDF download is still performed so that you have the URL.)
    private static void downloadPDF(String pdfUrl, String fileName) {
        String sanitized = sanitizeFilename(fileName);
        String filepath = OUTPUT_DIR + sanitized + ".pdf";
        File pdfFile = new File(filepath);
        if (pdfFile.exists()) {
            System.out.println("File exists: " + filepath);
            return;
        }
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpGet request = new HttpGet(pdfUrl);
        try (CloseableHttpResponse response = httpClient.execute(request);
             InputStream is = response.getEntity().getContent();
             FileOutputStream fos = new FileOutputStream(filepath)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            System.out.println("Downloaded: " + filepath + " at " + LocalDateTime.now());
        } catch (IOException e) {
            System.err.println("Failed to download " + pdfUrl + ": " + e.getMessage());
        } finally {
            try { httpClient.close(); } catch (IOException ignored) {}
        }
    }

    // Append metadata as JSON (one JSON object per line) to output.json
    private static void appendToJson(String jsonData) {
        synchronized (jsonLock) {
            try (FileWriter fw = new FileWriter(JSON_OUTPUT, true);
                 BufferedWriter bw = new BufferedWriter(fw)) {
                bw.write(jsonData);
                bw.newLine();
            } catch (IOException e) {
                System.err.println("Error appending to " + JSON_OUTPUT + ": " + e.getMessage());
            }
        }
    }

    // Append metadata as a CSV row to output.csv (write header if file does not exist)
    private static void appendToCsv(String title, String authors, String abs, String pdfUrl, String paperUrl) {
        synchronized (csvLock) {
            boolean writeHeader = !new File(CSV_OUTPUT).exists();
            try (FileWriter fw = new FileWriter(CSV_OUTPUT, true);
                 BufferedWriter bw = new BufferedWriter(fw);
                 PrintWriter out = new PrintWriter(bw)) {
                if (writeHeader) {
                    out.println("title,authors,abstract,pdf_url,paper_url");
                }
                // Escape double-quotes and wrap fields in quotes
                String row = String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"",
                        title.replace("\"", "\"\""),
                        authors.replace("\"", "\"\""),
                        abs.replace("\"", "\"\""),
                        pdfUrl.replace("\"", "\"\""),
                        paperUrl.replace("\"", "\"\""));
                out.println(row);
            } catch (IOException e) {
                System.err.println("Error appending to " + CSV_OUTPUT + ": " + e.getMessage());
            }
        }
    }

    // Process papers for any year > 2021 (using updated selectors)
    private static void processPaperNew(String paperUrl, int year) {
        int attempts = 0;
        while (attempts < MAX_RETRIES) {
            try {
                Document doc = Jsoup.connect(paperUrl).timeout(TIMEOUT).get();
                // Title: from the page title (remove trailing " - NeurIPS")
                String title = doc.title() != null ? doc.title().replace(" - NeurIPS", "").trim() : "Untitled";
                // Authors: look for an <h4> with "Authors" then its next <p>
                Element authorsH4 = doc.selectFirst("h4:containsOwn(Authors)");
                String authors = "";
                if (authorsH4 != null) {
                    Element authorsP = authorsH4.nextElementSibling();
                    if (authorsP != null) {
                        authors = authorsP.text().trim();
                    }
                }
                // Abstract: look for an <h4> with "Abstract" then its next <p>
                Element abstractH4 = doc.selectFirst("h4:containsOwn(Abstract)");
                String abs = "No abstract available";
                if (abstractH4 != null) {
                    Element abstractP = abstractH4.nextElementSibling();
                    if (abstractP != null) {
                        abs = abstractP.text().trim();
                    }
                }
                // PDF link: find <a> whose href ends with "Paper-Conference.pdf"
                Element pdfLink = doc.selectFirst("a[href$=Paper-Conference.pdf]");
                if (pdfLink == null) {
                    System.out.println("No PDF link found: " + paperUrl);
                    return;
                }
                String pdfUrl = BASE_URL + pdfLink.attr("href");
                // (Optionally, download the PDF)
                downloadPDF(pdfUrl, title);

                // Create metadata JSON string
                String metadataJson = "{\n" +
                        "  \"title\": \"" + title + "\",\n" +
                        "  \"authors\": \"" + authors + "\",\n" +
                        "  \"abstract\": \"" + abs + "\",\n" +
                        "  \"pdf_url\": \"" + pdfUrl + "\",\n" +
                        "  \"paper_url\": \"" + paperUrl + "\"\n" +
                        "}";
                // Save individual metadata file
                String metaFilename = METADATA_DIR + sanitizeFilename(title) + ".json";
                try (FileWriter fw = new FileWriter(metaFilename)) {
                    fw.write(metadataJson);
                }
                System.out.println("Saved metadata: " + metaFilename);
                // Incrementally append metadata to output files
                appendToJson(metadataJson);
                appendToCsv(title, authors, abs, pdfUrl, paperUrl);
                return;
            } catch (IOException e) {
                System.err.println("Attempt " + (attempts+1) + " failed for " + paperUrl + ": " + e.getMessage());
                attempts++;
                try {
                    Thread.sleep((long) Math.pow(2, attempts) * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        System.out.println("Giving up: " + paperUrl);
    }

    // Process papers for 2021 and earlier (using original selectors)
    private static void processPaperOld(String paperUrl, int year) {
        int attempts = 0;
        while (attempts < MAX_RETRIES) {
            try {
                Document doc = Jsoup.connect(paperUrl).timeout(TIMEOUT).get();
                // Title: first <h4> tag
                Element titleElem = doc.selectFirst("h4");
                String title = titleElem != null ? titleElem.text().trim() : "Untitled";
                // PDF link: <a> tag with href containing "Paper.pdf"
                Element pdfLink = doc.selectFirst("a[href*=Paper.pdf]");
                if (pdfLink == null) {
                    System.out.println("No PDF link: " + paperUrl);
                    return;
                }
                String pdfUrl = BASE_URL + pdfLink.attr("href");
                // Authors: find <h4> with "Authors" then next <p>
                Element authorsH4 = doc.selectFirst("h4:containsOwn(Authors)");
                String authors = "";
                if (authorsH4 != null) {
                    Element authorsP = authorsH4.nextElementSibling();
                    if (authorsP != null) {
                        authors = authorsP.text().trim();
                    }
                }
                // Abstract: find <h4> with "Abstract" then next non-empty <p>
                Element abstractH4 = doc.selectFirst("h4:containsOwn(Abstract)");
                String abs = "No abstract available";
                if (abstractH4 != null) {
                    Element abstractP = abstractH4.nextElementSibling();
                    while (abstractP != null && abstractP.text().trim().isEmpty()) {
                        abstractP = abstractP.nextElementSibling();
                    }
                    if (abstractP != null) {
                        abs = abstractP.text().trim();
                    }
                }
                // Optionally, download PDF
                downloadPDF(pdfUrl, title);

                // Create metadata JSON string
                String metadataJson = "{\n" +
                        "  \"title\": \"" + title + "\",\n" +
                        "  \"authors\": \"" + authors + "\",\n" +
                        "  \"abstract\": \"" + abs + "\",\n" +
                        "  \"pdf_url\": \"" + pdfUrl + "\",\n" +
                        "  \"paper_url\": \"" + paperUrl + "\"\n" +
                        "}";
                String metaFilename = METADATA_DIR + sanitizeFilename(title) + ".json";
                try (FileWriter fw = new FileWriter(metaFilename)) {
                    fw.write(metadataJson);
                }
                System.out.println("Saved metadata: " + metaFilename);
                // Incrementally append metadata to output files
                appendToJson(metadataJson);
                appendToCsv(title, authors, abs, pdfUrl, paperUrl);
                return;
            } catch (IOException e) {
                System.err.println("Attempt " + (attempts+1) + " failed for " + paperUrl + ": " + e.getMessage());
                attempts++;
                try {
                    Thread.sleep((long) Math.pow(2, attempts) * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        System.out.println("Giving up: " + paperUrl);
    }

    // (Optional) Process dataset and benchmark papers for 2021
    private static void processDatasetBenchmarkPapers(String url) {
        try {
            Document doc = Jsoup.connect(url).timeout(TIMEOUT).get();
            Elements paperLinks = doc.select("a[href$=Abstract.html]");
            for (Element link : paperLinks) {
                String paperUrl = BASE_URL + link.attr("href");
                processPaperOld(paperUrl, 2021);
            }
        } catch (IOException e) {
            System.err.println("Error processing datasets and benchmarks papers: " + e.getMessage());
        }
    }

    // Get user-specified years (up to 5) between START_YEAR and END_YEAR
    private static int[] getUserYears() throws IOException {
        System.out.print("Enter up to 5 years (comma-separated) between " + START_YEAR + " and " + END_YEAR + ": ");
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        String input = br.readLine();
        String[] parts = input.split(",");
        if (parts.length > 5) {
            System.out.println("Error: You can only select up to 5 years at a time.");
            return getUserYears();
        }
        int[] years = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            int yr = Integer.parseInt(parts[i].trim());
            if (yr < START_YEAR || yr > END_YEAR) {
                System.out.println("Error: Year " + yr + " is out of range.");
                return getUserYears();
            }
            years[i] = yr;
        }
        return years;
    }

    // Zip a folder (sourceFolder) into a zip file (zipFilePath)
    private static void zipFolder(String sourceFolder, String zipFilePath) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFilePath))) {
            Path sourcePath = Paths.get(sourceFolder);
            Files.walk(sourcePath)
                .filter(path -> !Files.isDirectory(path))
                .forEach(path -> {
                    ZipEntry zipEntry = new ZipEntry(sourcePath.relativize(path).toString());
                    try {
                        zos.putNextEntry(zipEntry);
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        System.err.println("Error zipping file: " + path + " : " + e.getMessage());
                    }
                });
        }
    }

    public static void main(String[] args) {
        try {
            // Create output directories
            Files.createDirectories(Paths.get(OUTPUT_DIR));
            Files.createDirectories(Paths.get(METADATA_DIR));

            int[] yearsToScrape = getUserYears();
            System.out.print("Scraping years: ");
            for (int yr : yearsToScrape) {
                System.out.print(yr + " ");
            }
            System.out.println();

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

            // Connect to the main page
            System.out.println("Connecting to main page: " + BASE_URL);
            Document mainPage = Jsoup.connect(BASE_URL).timeout(TIMEOUT).get();
            System.out.println("Successfully connected to main page.");

            // Select links to paper archive pages (URLs that start with /paper_files/paper/)
            Elements yearLinks = mainPage.select("a[href^=/paper_files/paper/]");
            System.out.println("Found " + yearLinks.size() + " paper archive links.");

            // Process each year link if it matches a user-specified year
            for (Element link : yearLinks) {
                String yearUrl = BASE_URL + link.attr("href");
                int year;
                try {
                    String[] parts = yearUrl.split("/");
                    year = Integer.parseInt(parts[parts.length - 1]);
                } catch (NumberFormatException e) {
                    System.out.println("Skipping invalid year URL: " + yearUrl);
                    continue;
                }

                boolean selected = false;
                for (int yr : yearsToScrape) {
                    if (yr == year) {
                        selected = true;
                        break;
                    }
                }
                if (!selected) {
                    System.out.println("Skipping year " + year + " (not selected)");
                    continue;
                }
                System.out.println("Processing year: " + year + " (" + yearUrl + ")");
                try {
                    Document yearPage = Jsoup.connect(yearUrl).timeout(TIMEOUT).get();
                    Elements paperLinks;
                    // For any year above 2021, use the updated selector and processing function.
                    if (year > 2021) {
                        paperLinks = yearPage.select("a[href$=Abstract-Conference.html]");
                    } else {
                        paperLinks = yearPage.select("a[href$=Abstract.html]");
                    }
                    System.out.println("Found " + paperLinks.size() + " paper links in year: " + yearUrl);
                    for (Element paperLink : paperLinks) {
                        String paperUrl = BASE_URL + paperLink.attr("href");
                        if (year > 2021) {
                            executor.submit(() -> processPaperNew(paperUrl, year));
                        } else {
                            executor.submit(() -> processPaperOld(paperUrl, year));
                        }
                    }
                } catch (IOException e) {
                    System.err.println("Year " + yearUrl + " error: " + e.getMessage());
                }
            }

            // Process dataset benchmark papers for 2021 if selected
            for (int yr : yearsToScrape) {
                if (yr == 2021) {
                    executor.submit(() -> processDatasetBenchmarkPapers(DATASETS_BENCHMARKS_URL_2021));
                    break;
                }
            }

            executor.shutdown();
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

            // Instead of zipping PDFs, only zip the metadata folder and incremental output files.
            String metadataZip = "metadata.zip";
            zipFolder(METADATA_DIR, metadataZip);
            System.out.println("Metadata folder zipped as " + metadataZip);
            // Additionally, zip the incremental files (output.json and output.csv) if desired.
            String incrementalZip = "incremental_metadata.zip";
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(incrementalZip))) {
                for (String fileName : new String[]{JSON_OUTPUT, CSV_OUTPUT}) {
                    File file = new File(fileName);
                    if (file.exists()) {
                        zos.putNextEntry(new ZipEntry(file.getName()));
                        Files.copy(file.toPath(), zos);
                        zos.closeEntry();
                    }
                }
            }
            System.out.println("Incremental metadata zipped as " + incrementalZip);

            // For a desktop Java app, you might open these files or move them to a desired location.
            // In a Colab environment you could upload them; here we simply print a message.
            System.out.println("Metadata extraction complete. Check " + metadataZip + " and " + incrementalZip);

        } catch (Exception e) {
            System.err.println("An error occurred during the scraping process.");
            e.printStackTrace();
        }
    }
}

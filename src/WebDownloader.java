import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import javax.net.ssl.*;
import java.security.cert.X509Certificate;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class WebDownloader {

    private static final String DB_URL = "jdbc:mysql://localhost:3306/web_downloader";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "";

    // Executor service for concurrent link downloading
    private static final ExecutorService executor = Executors.newFixedThreadPool(10);

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        String urlString = "";
        boolean validUrl = false;

        // Loop to ask the user for a valid URL until it's correct
        while (!validUrl) {
            System.out.println("Enter a valid website URL:");

            urlString = scanner.nextLine();

            try {
                // Try to create a URL object from the input
                new URL(urlString);

                // If successful, break the loop and proceed
                validUrl = true;

                // Disable SSL validation for testing (optional)
                disableSslVerification();

                // Continue with the rest of your program logic
                String domainName = new URL(urlString).getHost().replace("www.", "");
                File directory = new File(domainName);
                if (!directory.exists()) {
                    directory.mkdirs();
                }
                System.out.println("Created directory: " + directory.getAbsolutePath());

                long startTime = System.currentTimeMillis();
                int websiteId = saveWebsiteToDB(domainName, new Timestamp(startTime));

                downloadHomePage(urlString, directory.getAbsolutePath(), websiteId, startTime);
            } catch (MalformedURLException e) {
                // Catch invalid URL and prompt the user to try again
                System.out.println("Invalid URL. Please enter a valid one.");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Shutdown the executor once all tasks are completed
        executor.shutdown();
    }

    // Method to disable SSL certificate verification (useful for untrusted SSL certificates)
    public static void disableSslVerification() throws Exception {
        TrustManager[] trustAllCertificates = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }

                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }
                }
        };

        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAllCertificates, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

        // Set hostname verifier that bypasses validation
        HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
    }

    public static int saveWebsiteToDB(String websiteName, Timestamp startTime) {
        int websiteId = -1;
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            String insertWebsiteSQL = "INSERT INTO website (website_name, download_start_date_time) VALUES (?, ?)";
            PreparedStatement statement = connection.prepareStatement(insertWebsiteSQL, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, websiteName);
            statement.setTimestamp(2, startTime);
            statement.executeUpdate();

            ResultSet rs = statement.getGeneratedKeys();
            if (rs.next()) {
                websiteId = rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Error saving website to DB: " + e.getMessage());
        }
        return websiteId;
    }

    public static void downloadHomePage(String urlString, String saveDirectory, int websiteId, long startTime) {
        try {
            Document doc = Jsoup.connect(urlString).get();
            String fileName = "index.html";
            Path filePath = Paths.get(saveDirectory, fileName);
            Files.write(filePath, doc.outerHtml().getBytes());
            System.out.println("Downloaded homepage: " + filePath);

            extractAndDownloadLinks(doc, urlString, saveDirectory, websiteId);
            long endTime = System.currentTimeMillis();
            updateWebsiteCompletion(websiteId, new Timestamp(endTime), endTime - startTime);

        } catch (IOException e) {
            System.out.println("Error downloading homepage: " + e.getMessage());
        }
    }

    public static void extractAndDownloadLinks(Document doc, String baseUrl, String saveDirectory, int websiteId) {
        Elements links = doc.select("a[href]");
        for (Element link : links) {
            String absUrl = link.absUrl("href");
            if (!absUrl.isEmpty() && absUrl.startsWith("http")) {
                // Submit link for asynchronous download
                executor.submit(() -> downloadLinkResource(absUrl, saveDirectory, websiteId));
            }
        }
    }

    public static void downloadLinkResource(String link, String saveDirectory, int websiteId) {
        try {
            URL url = new URL(link);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36");
            connection.connect();
            int fileSize = connection.getContentLength();

            // Normalize file path for valid directory creation
            String filePath = url.getPath().replaceAll("[<>:\"/\\\\|?*]", "_");
            String fileName = Paths.get(filePath).getFileName().toString();
            if (fileName.isEmpty() || fileName.equals("/")) {
                fileName = "index.html";
            }

            // Create the directory structure if it doesn't exist
            Path outputPath = Paths.get(saveDirectory, filePath);
            Files.createDirectories(outputPath.getParent());
            File outputFile = new File(outputPath.toString());

            try (InputStream inputStream = connection.getInputStream();
                 OutputStream outputStream = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                long totalBytesRead = 0;
                long startTime = System.currentTimeMillis();

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                }
                long elapsedTime = System.currentTimeMillis() - startTime;
                System.out.printf("Downloaded %s in %d ms\n", fileName, elapsedTime);
                saveLinkToDB(link, websiteId, totalBytesRead / 1024, elapsedTime);

            } catch (IOException e) {
                System.err.println("Error saving file: " + e.getMessage());
            }
        } catch (IOException e) {
            System.err.println("Error downloading link: " + e.getMessage());
        }
    }

    public static void saveLinkToDB(String link, int websiteId, long kilobytes, long elapsedTime) {
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            String insertLinkSQL = "INSERT INTO link (link_name, website_id, total_elapsed_time, total_downloaded_kilobytes) VALUES (?, ?, ?, ?)";
            PreparedStatement statement = connection.prepareStatement(insertLinkSQL);
            statement.setString(1, link);
            statement.setInt(2, websiteId);
            statement.setLong(3, elapsedTime);
            statement.setLong(4, kilobytes);
            statement.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error saving link to DB: " + e.getMessage());
        }
    }

    public static void updateWebsiteCompletion(int websiteId, Timestamp endTime, long elapsedTime) {
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            String updateWebsiteSQL = "UPDATE website SET download_end_date_time = ?, total_elapsed_time = ? WHERE id = ?";
            PreparedStatement statement = connection.prepareStatement(updateWebsiteSQL);
            statement.setTimestamp(1, endTime);
            statement.setLong(2, elapsedTime);
            statement.setInt(3, websiteId);
            statement.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error updating website completion: " + e.getMessage());
        }
    }
}

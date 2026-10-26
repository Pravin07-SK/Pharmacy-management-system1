import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * GreenCare Pharmacy - simple Java HTTP backend.
 *
 * Run this file from the src folder:
 *   javac Main.java
 *   java Main
 *
 * The data files are saved in src/data. This is local file storage, not Supabase.
 */
public class Main {

    private static final int PORT = 8080;

    // Medicine fields: id, name, price, quantity
    private static final List<String[]> medicines = new ArrayList<>();

    // History fields: time, action, id, name, oldQuantity, newQuantity
    private static final List<String[]> stockHistory = new ArrayList<>();

    // Customer fields: phone/username, customerName, registeredAt, type
    private static final List<String[]> customers = new ArrayList<>();

    // Purchase fields: purchaseId, customerName, phone, purchasedAt,
    // itemSummary, total, cashback, status
    private static final List<String[]> purchases = new ArrayList<>();

    private static final Path DATA_FOLDER = Path.of("data");
    private static final Path MEDICINES_FILE = DATA_FOLDER.resolve("medicines.txt");
    private static final Path HISTORY_FILE = DATA_FOLDER.resolve("history.txt");
    private static final Path CUSTOMERS_FILE = DATA_FOLDER.resolve("customers.txt");
    private static final Path PURCHASES_FILE = DATA_FOLDER.resolve("purchases.txt");

    public static void main(String[] args) throws Exception {
        loadData();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Main::handleRequest);
        server.setExecutor(null);
        server.start();

        System.out.println("==================================");
        System.out.println(" GreenCare Pharmacy Started!");
        System.out.println("==================================");
        System.out.println("Website:   http://localhost:" + PORT + "/");
        System.out.println("Medicines: http://localhost:" + PORT + "/api/medicines");
        System.out.println("History:   http://localhost:" + PORT + "/api/history");
        System.out.println("Purchases: http://localhost:" + PORT + "/api/purchases");
        System.out.println("Customers: http://localhost:" + PORT + "/api/customers");
        System.out.println("Status:    http://localhost:" + PORT + "/api/status");
        System.out.println("Storage:   " + DATA_FOLDER.toAbsolutePath());
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            if (path.equals("/") && method.equals("GET")) {
                serveWebsite(exchange);
            } else if (path.equals("/api/status") && method.equals("GET")) {
                send(exchange, 200, "text/plain",
                        "GreenCare Pharmacy Java backend is running!");
            } else if (path.equals("/api/medicines") && method.equals("GET")) {
                send(exchange, 200, "application/json", medicinesJson());
            } else if (path.equals("/api/history") && method.equals("GET")) {
                send(exchange, 200, "application/json", historyJson());
            } else if (path.equals("/api/customers") && method.equals("GET")) {
                send(exchange, 200, "application/json", customersJson());
            } else if (path.equals("/api/purchases") && method.equals("GET")) {
                send(exchange, 200, "application/json", purchasesJson());
            } else if (path.equals("/api/add") && method.equals("POST")) {
                addMedicine(exchange);
            } else if (path.equals("/api/edit") && method.equals("POST")) {
                editMedicine(exchange);
            } else if (path.equals("/api/delete") && method.equals("POST")) {
                deleteMedicine(exchange);
            } else if (path.equals("/api/checkout") && method.equals("POST")) {
                checkout(exchange);

            } else if (path.equals("/api/delete-purchases") && method.equals("POST")) {
                deleteCustomerPurchases(exchange);
            } else {
                send(exchange, 404, "text/plain", "API endpoint not found.");
            }
        } catch (Exception exception) {
            exception.printStackTrace();
            send(exchange, 500, "text/plain",
                    "Server error. Check the VS Code terminal for details.");
        } finally {
            exchange.close();
        }
    }

    private static void serveWebsite(HttpExchange exchange) throws IOException {
        Path file = Path.of("..", "web", "index.html");
        if (!Files.exists(file)) {
            file = Path.of("web", "index.html");
        }

        if (!Files.exists(file)) {
            send(exchange, 404, "text/plain",
                    "Website file not found. Run Main.java from the src folder and check the web folder.");
            return;
        }

        byte[] page = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, page.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(page);
        }
    }

    private static void addMedicine(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        String id = value(form, "id");
        String name = value(form, "name");
        String priceText = value(form, "price");
        String quantityText = value(form, "quantity");

        if (id.isEmpty() || name.isEmpty()) {
            send(exchange, 400, "text/plain", "Medicine ID and name are required.");
            return;
        }

        double price;
        int quantity;
        try {
            price = Double.parseDouble(priceText);
            quantity = Integer.parseInt(quantityText);
        } catch (NumberFormatException exception) {
            send(exchange, 400, "text/plain", "Enter a valid price and quantity.");
            return;
        }

        if (!Double.isFinite(price) || price <= 0 || quantity < 0) {
            send(exchange, 400, "text/plain", "Price must be above 0 and quantity cannot be negative.");
            return;
        }

        if (findMedicine(id) != null) {
            send(exchange, 409, "text/plain", "Medicine ID already exists.");
            return;
        }

        synchronized (medicines) {
            medicines.add(new String[] {
                    id, name, money(price), String.valueOf(quantity)
            });
            addHistory("ADDED", id, name, "-", String.valueOf(quantity));
            saveData();
        }
        send(exchange, 201, "text/plain", "Medicine added successfully!");
    }

    private static void editMedicine(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        String id = value(form, "id");
        String name = value(form, "name");
        String priceText = value(form, "price");
        String quantityText = value(form, "quantity");

        String[] medicine = findMedicine(id);
        if (medicine == null) {
            send(exchange, 404, "text/plain", "Medicine not found. Refresh the inventory and try again.");
            return;
        }
        if (name.isEmpty()) {
            send(exchange, 400, "text/plain", "Medicine name is required.");
            return;
        }

        double price;
        int quantity;
        try {
            price = Double.parseDouble(priceText);
            quantity = Integer.parseInt(quantityText);
        } catch (NumberFormatException exception) {
            send(exchange, 400, "text/plain", "Enter a valid price and quantity.");
            return;
        }

        if (!Double.isFinite(price) || price <= 0 || quantity < 0) {
            send(exchange, 400, "text/plain", "Price must be above 0 and quantity cannot be negative.");
            return;
        }

        synchronized (medicines) {
            String oldName = medicine[1];
            String oldQuantity = medicine[3];
            medicine[1] = name;
            medicine[2] = money(price);
            medicine[3] = String.valueOf(quantity);

            addHistory("EDITED", id, oldName + " -> " + name,
                    oldQuantity, String.valueOf(quantity));
            saveData();
        }
        send(exchange, 200, "text/plain", "Medicine updated successfully!");
    }

    private static void deleteMedicine(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        String id = value(form, "id");
        String[] medicine = findMedicine(id);

        if (medicine == null) {
            send(exchange, 404, "text/plain", "Medicine not found.");
            return;
        }

        synchronized (medicines) {
            medicines.remove(medicine);
            addHistory("DELETED", medicine[0], medicine[1], medicine[3], "0");
            saveData();
        }
        send(exchange, 200, "text/plain", "Medicine deleted successfully!");
    }

private static void deleteCustomerPurchases(HttpExchange exchange)
        throws IOException {

    Map<String, String> form = readForm(exchange);
    String phone = value(form, "phone");

    if (!phone.matches("\\d{10}")) {
        send(exchange, 400, "text/plain",
                "Enter a valid 10-digit phone number.");
        return;
    }

    synchronized (purchases) {
        int oldCount = purchases.size();

        purchases.removeIf(purchase ->
                purchase.length >= 3
                && purchase[2].equals(phone));

        int deletedCount = oldCount - purchases.size();

        if (deletedCount == 0) {
            send(exchange, 404, "text/plain",
                    "No purchase records found for this phone number.");
            return;
        }

        saveData();

        send(exchange, 200, "text/plain",
                "Deleted " + deletedCount
                + " purchase record(s). Medicine stock is unchanged.");
    }
}


    /**
     * Checkout accepts:
     * username, customerName, customerPhone, count,
     * id0/quantity0, id1/quantity1, ...
     *
     * Every item is checked before stock is changed. Repeated medicine IDs are
     * combined for stock validation so the same stock cannot be sold twice.
     */
    private static void checkout(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);

        String username = value(form, "username");
        String customerName = value(form, "customerName");
        String customerPhone = value(form, "customerPhone");

        // Fill missing customer details from a saved customer record.
        for (String[] customer : customers) {
            if (customer.length >= 4
                    && (customer[0].equalsIgnoreCase(username)
                    || customer[0].equalsIgnoreCase(customerPhone))) {
                if (customerName.isEmpty()) {
                    customerName = customer[1];
                }
                if (customerPhone.isEmpty()) {
                    customerPhone = customer[0];
                }
                break;
            }
        }

        // Some versions of the page use the phone number as the login username.
        if (customerPhone.isEmpty() && username.matches("\\d{10}")) {
            customerPhone = username;
        }

        int count;
        try {
            count = Integer.parseInt(valueOr(form, "count", "0"));
        } catch (NumberFormatException exception) {
            send(exchange, 400, "text/plain", "Invalid item count.");
            return;
        }

        if (username.isEmpty() || customerName.isEmpty()
                || !customerPhone.matches("\\d{10}")
                || count < 1 || count > 100) {
            send(exchange, 400, "text/plain",
                    "Customer name, valid 10-digit phone number, and valid cart items are required.");
            return;
        }

        // Combine duplicate IDs and validate all items before changing inventory.
        Map<String, Integer> quantityById = new java.util.LinkedHashMap<>();
        try {
            for (int i = 0; i < count; i++) {
                String id = value(form, "id" + i);
                int quantity = Integer.parseInt(valueOr(form, "quantity" + i, "0"));

                if (id.isEmpty() || quantity < 1) {
                    send(exchange, 400, "text/plain", "Each medicine must have a valid ID and quantity of at least 1.");
                    return;
                }
                quantityById.merge(id.toUpperCase(Locale.ROOT), quantity, Integer::sum);
            }
        } catch (NumberFormatException exception) {
            send(exchange, 400, "text/plain", "Invalid medicine quantity.");
            return;
        }

        synchronized (medicines) {
            List<String[]> orderedMedicines = new ArrayList<>();
            List<Integer> quantities = new ArrayList<>();
            double total = 0.0;

            for (Map.Entry<String, Integer> item : quantityById.entrySet()) {
                String[] medicine = findMedicine(item.getKey());
                int quantity = item.getValue();

                if (medicine == null) {
                    send(exchange, 400, "text/plain", "Medicine not found: " + item.getKey());
                    return;
                }

                int available;
                double price;
                try {
                    available = Integer.parseInt(medicine[3]);
                    price = Double.parseDouble(medicine[2]);
                } catch (NumberFormatException exception) {
                    send(exchange, 500, "text/plain", "Invalid stock or price saved for " + medicine[1]);
                    return;
                }

                if (quantity > available) {
                    send(exchange, 400, "text/plain",
                            "Insufficient stock for " + medicine[1] + ". Available quantity: " + available);
                    return;
                }

                orderedMedicines.add(medicine);
                quantities.add(quantity);
                total += price * quantity;
            }

            if (!Double.isFinite(total)) {
                send(exchange, 400, "text/plain", "Invalid bill total.");
                return;
            }

            // Build item-wise bill: medicine name x quantity = line total.
            StringBuilder summary = new StringBuilder();
            for (int i = 0; i < orderedMedicines.size(); i++) {
                String[] medicine = orderedMedicines.get(i);
                int quantity = quantities.get(i);
                double lineTotal = Double.parseDouble(medicine[2]) * quantity;

                if (i > 0) {
                    summary.append("; ");
                }
                summary.append(medicine[1])
                        .append(" x ").append(quantity)
                        .append(" @ ₹").append(money(Double.parseDouble(medicine[2])))
                        .append(" = ₹").append(money(lineTotal));
            }

            // Update stock and add stock-history rows after validation succeeds.
            for (int i = 0; i < orderedMedicines.size(); i++) {
                String[] medicine = orderedMedicines.get(i);
                int quantity = quantities.get(i);
                int oldQuantity = Integer.parseInt(medicine[3]);
                int newQuantity = oldQuantity - quantity;

                medicine[3] = String.valueOf(newQuantity);
                addHistory("SOLD", medicine[0], medicine[1],
                        String.valueOf(oldQuantity), String.valueOf(newQuantity));
            }

            total = Math.round(total * 100.0) / 100.0;
            double cashback = Math.round(total * 0.01 * 100.0) / 100.0;
            String purchaseId = "P" + System.currentTimeMillis();
            String time = LocalDateTime.now().toString();

            purchases.add(new String[] {
                    purchaseId,
                    customerName,
                    customerPhone,
                    time,
                    summary.toString(),
                    money(total),
                    money(cashback),
                    "COMPLETED"
            });

            boolean customerExists = false;
            for (String[] customer : customers) {
                if (customer.length >= 1 && customer[0].equalsIgnoreCase(customerPhone)) {
                    customerExists = true;
                    // Keep the customer's latest name if it was previously blank.
                    if (customer.length >= 2 && customer[1].isBlank()) {
                        customer[1] = customerName;
                    }
                    break;
                }
            }

            if (!customerExists) {
                customers.add(new String[] {
                        customerPhone, customerName, time, "CUSTOMER"
                });
            }

            saveData();

            String response = "{\"purchaseId\":\"" + escapeJson(purchaseId)
                    + "\",\"total\":" + money(total)
                    + ",\"cashback\":" + money(cashback)
                    + ",\"message\":\"Purchase completed successfully.\"}";
            send(exchange, 200, "application/json", response);
        }
    }

    private static String medicinesJson() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < medicines.size(); i++) {
            String[] medicine = medicines.get(i);
            if (i > 0) json.append(",");
            json.append("{")
                    .append("\"id\":\"").append(escapeJson(medicine[0])).append("\",")
                    .append("\"name\":\"").append(escapeJson(medicine[1])).append("\",")
                    .append("\"price\":").append(jsonNumber(medicine[2])).append(",")
                    .append("\"quantity\":").append(jsonNumber(medicine[3]))
                    .append("}");
        }
        return json.append("]").toString();
    }

    private static String historyJson() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < stockHistory.size(); i++) {
            String[] history = stockHistory.get(i);
            if (i > 0) json.append(",");
            json.append("{")
                    .append("\"time\":\"").append(escapeJson(history[0])).append("\",")
                    .append("\"action\":\"").append(escapeJson(history[1])).append("\",")
                    .append("\"id\":\"").append(escapeJson(history[2])).append("\",")
                    .append("\"name\":\"").append(escapeJson(history[3])).append("\",")
                    .append("\"oldQuantity\":\"").append(escapeJson(history[4])).append("\",")
                    .append("\"newQuantity\":\"").append(escapeJson(history[5])).append("\"")
                    .append("}");
        }
        return json.append("]").toString();
    }

    private static String customersJson() {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (String[] customer : customers) {
            if (customer.length < 4) continue;
            if (customer[0].equalsIgnoreCase("admin")
                    || customer[3].equalsIgnoreCase("ADMIN")) continue;

            if (!first) json.append(",");
            first = false;
            json.append("{")
                    .append("\"phone\":\"").append(escapeJson(customer[0])).append("\",")
                    .append("\"customerName\":\"").append(escapeJson(customer[1])).append("\",")
                    .append("\"registeredAt\":\"").append(escapeJson(customer[2])).append("\",")
                    .append("\"type\":\"").append(escapeJson(customer[3])).append("\"")
                    .append("}");
        }
        return json.append("]").toString();
    }

    private static String purchasesJson() {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (String[] purchase : purchases) {
            if (purchase.length < 8) continue;
            if (!first) json.append(",");
            first = false;
            json.append("{")
                    .append("\"purchaseId\":\"").append(escapeJson(purchase[0])).append("\",")
                    .append("\"customerName\":\"").append(escapeJson(purchase[1])).append("\",")
                    .append("\"phone\":\"").append(escapeJson(purchase[2])).append("\",")
                    .append("\"purchasedAt\":\"").append(escapeJson(purchase[3])).append("\",")
                    .append("\"medicines\":\"").append(escapeJson(purchase[4])).append("\",")
                    .append("\"total\":").append(jsonNumber(purchase[5])).append(",")
                    .append("\"cashback\":").append(jsonNumber(purchase[6])).append(",")
                    .append("\"status\":\"").append(escapeJson(purchase[7])).append("\"")
                    .append("}");
        }
        return json.append("]").toString();
    }

    private static void loadData() throws IOException {
        Files.createDirectories(DATA_FOLDER);

        if (Files.exists(MEDICINES_FILE)) {
            for (String line : Files.readAllLines(MEDICINES_FILE, StandardCharsets.UTF_8)) {
                try {
                    String[] fields = decodeLine(line);
                    if (fields.length == 4) medicines.add(fields);
                } catch (IllegalArgumentException exception) {
                    System.err.println("Skipping invalid medicine data line.");
                }
            }
        } else {
            medicines.add(new String[] {"M001", "Paracetamol", "50.00", "100"});
            medicines.add(new String[] {"M002", "Vitamin C", "80.00", "40"});
            medicines.add(new String[] {"M003", "Antiseptic Cream", "65.00", "25"});
            saveMedicines();
        }

        loadRecords(HISTORY_FILE, stockHistory, 6);
        loadRecords(CUSTOMERS_FILE, customers, 4);
        loadRecords(PURCHASES_FILE, purchases, 8);

        // Backward compatibility with older 3-field customer records.
        if (Files.exists(CUSTOMERS_FILE) && customers.isEmpty()) {
            for (String line : Files.readAllLines(CUSTOMERS_FILE, StandardCharsets.UTF_8)) {
                try {
                    String[] fields = decodeLine(line);
                    if (fields.length == 3) {
                        customers.add(new String[] {fields[0], "", fields[1], fields[2]});
                    }
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed legacy line.
                }
            }
        }
    }

    private static void loadRecords(Path file, List<String[]> destination, int expectedFields)
            throws IOException {
        if (!Files.exists(file)) return;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            try {
                String[] fields = decodeLine(line);
                if (fields.length == expectedFields) destination.add(fields);
            } catch (IllegalArgumentException exception) {
                System.err.println("Skipping invalid data in " + file.getFileName());
            }
        }
    }

    private static void saveData() throws IOException {
        Files.createDirectories(DATA_FOLDER);
        saveMedicines();
        saveRecords(HISTORY_FILE, stockHistory);
        saveRecords(CUSTOMERS_FILE, customers);
        saveRecords(PURCHASES_FILE, purchases);
    }

    private static void saveMedicines() throws IOException {
        saveRecords(MEDICINES_FILE, medicines);
    }

    private static void saveRecords(Path file, List<String[]> records) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String[] record : records) lines.add(encodeLine(record));
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static String encodeLine(String[] fields) {
        StringJoiner joiner = new StringJoiner("\t");
        for (String field : fields) {
            joiner.add(Base64.getEncoder().encodeToString(
                    field.getBytes(StandardCharsets.UTF_8)));
        }
        return joiner.toString();
    }

    private static String[] decodeLine(String line) {
        String[] parts = line.split("\t", -1);
        String[] result = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = new String(Base64.getDecoder().decode(parts[i]),
                    StandardCharsets.UTF_8);
        }
        return result;
    }

    private static String[] findMedicine(String id) {
        for (String[] medicine : medicines) {
            if (medicine[0].equalsIgnoreCase(id)) return medicine;
        }
        return null;
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> result = new HashMap<>();
        if (body.isEmpty()) return result;

        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    private static void addHistory(String action, String id, String name,
                                   String oldQuantity, String newQuantity) {
        stockHistory.add(new String[] {
                LocalDateTime.now().toString(), action, id, name, oldQuantity, newQuantity
        });
    }

    private static String value(Map<String, String> form, String key) {
        return form.getOrDefault(key, "").trim();
    }

    private static String valueOr(Map<String, String> form, String key, String fallback) {
        String value = form.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String money(double amount) {
        return String.format(Locale.US, "%.2f", amount);
    }

    private static String jsonNumber(String value) {
        try {
            double number = Double.parseDouble(value);
            if (!Double.isFinite(number)) return "0";
            return money(number);
        } catch (NumberFormatException exception) {
            return "0";
        }
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static void send(HttpExchange exchange, int status,
                             String contentType, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

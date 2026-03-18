package org.evombt;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.fbk.iv4xr.mbt.efsm.EFSM;
import eu.fbk.iv4xr.mbt.efsm.EFSMTransition;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class OrderRunner {
    private static final String API_URL = System.getenv().getOrDefault("API_URL", "http://localhost:8000/orders");
    private static final String BASE_URL = API_URL.replace("/orders", "");
    private static final OkHttpClient client = new OkHttpClient();
    private static final Gson gson = new Gson();

    private static int currentOrderId = -1;
    private static String lastSutResult = "";

    public static void main(String[] args) throws Exception {
        System.out.println("Starting EvoMBT CRUD Test Runner for E-Commerce API...");

        // Ensure clean DB
        clearAllOrders();

        // Start tracking a new order
        startNewOrder();

        OrderEFSM efsmBuilder = new OrderEFSM();
        EFSM efsm = efsmBuilder.getModel();

        int steps = 20; // Generate 20 steps
        long startTime = System.currentTimeMillis();

        for (int i = 1; i <= steps; i++) {
            // Find all valid transitions from the current state
            Set<EFSMTransition> validOutTransitions = efsm.transitionsOutOf(efsm.getConfiguration().getState());
            List<EFSMTransition> feasibleTransitions = new ArrayList<>();

            for (EFSMTransition t : validOutTransitions) {
                if (t.isFeasible(efsm.getConfiguration().getContext())) {
                    feasibleTransitions.add(t);
                }
            }

            if (feasibleTransitions.isEmpty()) {
                System.out.println("Step " + i + ": No valid transitions found. Testing blocked.");
                break;
            }

            // Pick a random valid transition
            EFSMTransition selected = feasibleTransitions.get(new Random().nextInt(feasibleTransitions.size()));
            
            // Execute on the real SUT (the API)
            executeOnSUT(selected.getId());

            // Transition the EFSM model
            efsm.transition(null, selected.getTgt());
            String newState = efsm.getConfiguration().getState().getId();

            // Post the result to the dashboard log
            logStep(newState, selected.getId(), lastSutResult);

            // Print to console
            System.out.println("Step " + i + " [" + newState + "] -> " + selected.getId() + " | SUT: " + lastSutResult);

            // Sleep so we can see the changes in the HTML dashboard stream
            Thread.sleep(600);
        }

        long timeTaken = System.currentTimeMillis() - startTime;
        System.out.println("\n--- Testing Completed ---");
        System.out.println("Total transitions executed: " + steps);
        System.out.println("Time taken: " + timeTaken + " ms");
        System.out.println("Average time per step: " + (timeTaken / steps) + " ms");
    }

    private static void logStep(String state, String transition, String sut) throws IOException {
        String json = String.format("{\"state\":\"%s\",\"transition\":\"%s\",\"sut\":\"%s\"}",
            state, transition, sut.replace("\"", "'"));
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));
        Request req = new Request.Builder().url(BASE_URL + "/test-log").post(body).build();
        try (Response r = client.newCall(req).execute()) { /* fire and forget */ }
    }

    private static void executeOnSUT(String transitionId) throws IOException {
        long t = System.currentTimeMillis();
        switch (transitionId) {
            case "t_addItem":
                addItem(); break;
            case "t_checkout":
                checkout(); break;
            case "t_pay":
                pay(); break;
            case "t_ship":
                ship(); break;
            case "t_cancelCart":
            case "t_cancelPending":
                cancel(); break;
            case "t_newOrderFromShipped":
            case "t_newOrderFromCancelled":
                startNewOrder(); break;
            default:
                lastSutResult = "Ignored " + transitionId;
        }
        long ms = System.currentTimeMillis() - t;
        lastSutResult += " (" + ms + "ms)";
    }

    // --- SUT Interaction Methods ----------------------------------

    private static void startNewOrder() throws IOException {
        RequestBody body = RequestBody.create("{}", MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder().url(API_URL).post(body).build();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                JsonObject obj = JsonParser.parseString(response.body().string()).getAsJsonObject();
                currentOrderId = obj.get("id").getAsInt();
                lastSutResult = "Created order #" + currentOrderId;
            } else {
                lastSutResult = "Failed to create order (HTTP " + response.code() + ")";
            }
        }
    }

    private static void addItem() throws IOException {
        RequestBody body = RequestBody.create("{}", MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder().url(API_URL + "/" + currentOrderId + "/items").post(body).build();
        try (Response response = client.newCall(request).execute()) {
            lastSutResult = "Added item to order #" + currentOrderId + " \u2192 HTTP " + response.code();
        }
    }

    private static void checkout() throws IOException {
        RequestBody body = RequestBody.create("{}", MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder().url(API_URL + "/" + currentOrderId + "/checkout").put(body).build();
        try (Response response = client.newCall(request).execute()) {
            lastSutResult = "Checkout order #" + currentOrderId + " \u2192 HTTP " + response.code();
        }
    }

    private static void pay() throws IOException {
        RequestBody body = RequestBody.create("{}", MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder().url(API_URL + "/" + currentOrderId + "/pay").put(body).build();
        try (Response response = client.newCall(request).execute()) {
            lastSutResult = "Paid order #" + currentOrderId + " \u2192 HTTP " + response.code();
        }
    }

    private static void ship() throws IOException {
        RequestBody body = RequestBody.create("{}", MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder().url(API_URL + "/" + currentOrderId + "/ship").put(body).build();
        try (Response response = client.newCall(request).execute()) {
            lastSutResult = "Shipped order #" + currentOrderId + " \u2192 HTTP " + response.code();
        }
    }

    private static void cancel() throws IOException {
        RequestBody body = RequestBody.create("{}", MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder().url(API_URL + "/" + currentOrderId + "/cancel").put(body).build();
        try (Response response = client.newCall(request).execute()) {
            lastSutResult = "Cancelled order #" + currentOrderId + " \u2192 HTTP " + response.code();
        }
    }

    private static void clearAllOrders() throws IOException {
        Request request = new Request.Builder().url(API_URL).delete().build();
        try (Response response = client.newCall(request).execute()) {
            System.out.println("Cleared DB. Status: " + response.code());
        }
    }
}

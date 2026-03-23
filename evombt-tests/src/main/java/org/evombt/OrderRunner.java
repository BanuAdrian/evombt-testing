package org.evombt;

import eu.fbk.iv4xr.mbt.efsm.EFSM;
import eu.fbk.iv4xr.mbt.efsm.EFSMTransition;
import okhttp3.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public class OrderRunner {
    private static final String API_URL = "http://localhost:8000/orders";
    private static final OkHttpClient client = new OkHttpClient();
    private static int currentOrderId = -1;

    public static void main(String[] args) throws Exception {
        System.out.println("Starting execution of the full EvoSuite Test Suite...");

        // 1. Dynamic path discovery for offline test generation
        String basePath = "mbt-files/tests/org.evombt.OrderEFSM/MOSA/";
        File mosaDir = new File(basePath);

        if (!mosaDir.exists() || !mosaDir.isDirectory()) {
            System.err.println("Error: MOSA results directory not found at " + basePath);
            System.err.println("Please run the EvoMBT test generation tool first.");
            return;
        }

        File[] subfolders = mosaDir.listFiles(File::isDirectory);
        if (subfolders == null || subfolders.length == 0) {
            System.err.println("Error: No timestamp folders found in " + basePath);
            return;
        }

        Arrays.sort(subfolders, (f1, f2) -> f2.getName().compareTo(f1.getName()));
        File latestFolder = subfolders[0];
        
        System.out.println("Detected latest test session: " + latestFolder.getName());

        File[] testFiles = latestFolder.listFiles((dir, name) -> name.startsWith("test_") && name.endsWith(".txt"));

        if (testFiles == null || testFiles.length == 0) {
            System.err.println("Error: No test files found in: " + latestFolder.getAbsolutePath());
            return;
        }

        System.out.println("Found " + testFiles.length + " test scenarios. Starting execution...");

        // Clear once before ALL tests so each test gets a unique incrementing Order ID
        clearOrders();

        for (File testFile : testFiles) {
            System.out.println("\n==========================================");
            System.out.println("Executing Scenario: " + testFile.getName());
            System.out.println("==========================================");

            OrderEFSM model = new OrderEFSM();
            EFSM efsm = model.getModel();
            
            JsonObject newOrder = createOrder();
            currentOrderId = newOrder.get("id").getAsInt();
            int sutWallet = newOrder.get("wallet_amount").getAsInt();
            System.out.println("Sync: SUT created Order #" + currentOrderId + " | Wallet: " + sutWallet + " RON");
            
            efsm.getConfiguration().getContext().getContext().getVariable("walletAmount").setValue(sutWallet);

            List<String> steps = Files.readAllLines(testFile.toPath());
            boolean scenarioPassed = true;

            for (String rawLine : steps) {
                rawLine = rawLine.trim();
                
                // 1. Ignore comments and empty lines from the raw EvoSuite file
                if (rawLine.isEmpty() || rawLine.startsWith("#")) continue;

                String stateId = efsm.getConfiguration().getState().getId();
                
                // 2. Parse the abstract line (e.g., "Empty-{ }->N_Items") to find the target state
                String[] parts = rawLine.split("-\\{.*\\}->");
                if (parts.length != 2) continue;
                String targetState = parts[1].trim();

                // 3. Translate: Find which feasible transition in our model leads to that target state
                EFSMTransition transitionToTake = null;
                for (Object tObj : efsm.transitionsOutOf(efsm.getConfiguration().getState())) {
                    EFSMTransition t = (EFSMTransition) tObj;
                    if (t.getTgt().getId().equals(targetState)) {
                        if (t.isFeasible(efsm.getConfiguration().getContext())) {
                            transitionToTake = t;
                            break;
                        }
                    }
                }

                if (transitionToTake == null) {
                    System.out.println("Warning: No feasible transition found to reach " + targetState);
                    scenarioPassed = false;
                    break;
                }

                // Now we have the correct, concrete action ID (e.g., "t_addItem_1")
                String actionId = transitionToTake.getId(); 
                System.out.printf("Step: [%-15s] -> %-20s (EvoSuite: %s)%n", stateId, actionId, rawLine);
                
                // 4. Execute the concrete action on the Python API SUT
                JsonObject sutState = executeOnSUT(actionId);
                
                if (sutState == null) {
                    System.out.println("FATAL ERROR: SUT returned null for action " + actionId);
                    scenarioPassed = false;
                    break;
                }

                if (sutState.has("error")) {
                    System.out.println("ORACLE ERROR: SUT rejected the transition!");
                    postTestLog(currentOrderId, stateId, actionId + " [FAIL]", sutState);
                    scenarioPassed = false;
                    break; 
                }

                // 5. Update the internal state of the EFSM (Oracle)
                efsm.transition(null, transitionToTake.getTgt());
                
                // 6. Synchronize context variables dynamically based on real SUT data
                efsm.getConfiguration().getContext().getContext().getVariable("itemsCount").setValue(sutState.get("items_count").getAsInt());
                efsm.getConfiguration().getContext().getContext().getVariable("cost").setValue(sutState.get("total_price").getAsInt());

                postTestLog(currentOrderId, stateId, actionId, sutState);
                Thread.sleep(300); // Small delay for smooth dashboard visualization
            }

            // Signal end of this test scenario to dashboard
            if (scenarioPassed) {
                postTestLog(currentOrderId, efsm.getConfiguration().getState().getId(), "TERMINAL", null);
            }
            System.out.println("Scenario execution finished.");
        }
        System.out.println("\nAll test scenarios in the suite have been executed!");
    }

    private static JsonObject executeOnSUT(String action) throws Exception {
        if (currentOrderId == -1) return null;
        String url = API_URL + "/" + currentOrderId;
        Request req = null;
        RequestBody empty = RequestBody.create("", null);

        // Strip the unique suffix from the EFSM transitioning ID (e.g. t_addItem_1 -> t_addItem)
        String baseAction = action.replaceAll("_\\d+$", "");

        switch (baseAction) {
            case "t_addItem": req = new Request.Builder().url(url + "/items").post(empty).build(); break;
            case "t_applyVoucher": req = new Request.Builder().url(url + "/voucher").post(empty).build(); break;
            case "t_checkout": req = new Request.Builder().url(url + "/checkout").put(empty).build(); break;
            case "t_payWithWallet": req = new Request.Builder().url(url + "/pay/wallet").put(empty).build(); break;
            case "t_payExternal": req = new Request.Builder().url(url + "/pay/external").put(empty).build(); break;
            case "t_externalPaySuccess": req = new Request.Builder().url(url + "/pay/external/success").put(empty).build(); break;
            case "t_externalPayFail": req = new Request.Builder().url(url + "/pay/external/fail").put(empty).build(); break;
            
            // Reversible arcs
            case "t_deleteItem": req = new Request.Builder().url(url + "/items").delete().build(); break;
            case "t_cancelCheckout": req = new Request.Builder().url(url + "/checkout/cancel").put(empty).build(); break;
            case "t_cancelPayment": req = new Request.Builder().url(url + "/pay/external/cancel").put(empty).build(); break;
            case "t_retryPayment": req = new Request.Builder().url(url + "/pay/external/retry").put(empty).build(); break;
        }

        if (req != null) {
            try (Response r = client.newCall(req).execute()) {
                if (!r.isSuccessful()) {
                    String errBody = r.body().string();
                    System.err.println("SUT Error on " + action + ": HTTP " + r.code() + " " + errBody);
                    JsonObject errObj = new JsonObject();
                    errObj.addProperty("error", "HTTP " + r.code() + " - " + errBody);
                    return errObj;
                } else {
                    return JsonParser.parseString(r.body().string()).getAsJsonObject();
                }
            }
        }
        return null;
    }

    private static JsonObject createOrder() throws Exception {
        Request req = new Request.Builder().url(API_URL).post(RequestBody.create("", null)).build();
        try (Response r = client.newCall(req).execute()) {
            return JsonParser.parseString(r.body().string()).getAsJsonObject();
        }
    }

    private static void clearOrders() throws Exception {
        Request req = new Request.Builder().url(API_URL).delete().build();
        try (Response r = client.newCall(req).execute()) {}
    }

    private static void postTestLog(int orderId, String state, String trans, JsonObject sutState) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("orderId", orderId);
            json.addProperty("state", state);
            json.addProperty("transition", trans);
            if (sutState != null) json.add("sut", sutState);
            
            RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json"));
            Request req = new Request.Builder().url("http://localhost:8000/test-log").post(body).build();
            client.newCall(req).execute().close();
        } catch (Exception e) {}
    }
}
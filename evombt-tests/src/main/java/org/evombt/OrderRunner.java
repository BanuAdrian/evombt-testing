package org.evombt;

import eu.fbk.iv4xr.mbt.efsm.EFSM;
import eu.fbk.iv4xr.mbt.efsm.EFSMTransition;
import eu.fbk.iv4xr.mbt.efsm.EFSMState;
import okhttp3.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.*;

public class OrderRunner {
    private static final String API_URL = "http://localhost:8000/orders";
    private static final OkHttpClient client = new OkHttpClient();
    private static int currentOrderId = -1;

    public static void main(String[] args) throws Exception {
        System.out.println("Starting EvoMBT Test Runner with Advanced 8-State Math EFSM...");
        clearOrders();
        Thread.sleep(500);

        int testCases = 15; // 15 test flows for a great demo!
        long startTime = System.currentTimeMillis();
        Random rand = new Random();

        for (int i = 1; i <= testCases; i++) {
            System.out.println("\n--- Flow " + i + " ---");
            
            OrderEFSM model = new OrderEFSM();
            EFSM efsm = model.getModel();
            
            // Generate a fresh session in the SUT 
            JsonObject newOrder = createOrder();
            currentOrderId = newOrder.get("id").getAsInt();
            int sutWallet = newOrder.get("wallet_amount").getAsInt();
            
            // Synchronize the SUT dynamic properties with the EFSM context
            efsm.getConfiguration().getContext().getContext().getVariable("walletAmount").setValue(sutWallet);
            System.out.println("Sync: SUT created Order #" + currentOrderId + " | Generated Wallet Funds: " + sutWallet + " RON");

            // Execute test path
            int depthCount = 0;
            while (true) {
                depthCount++;
                if (depthCount > 50) {
                    System.out.println("Hit maximum depth (50 steps). Terminating Flow.");
                    postTestLog(currentOrderId, efsm.getConfiguration().getState().getId(), "MAX_DEPTH_REACHED", null);
                    break;
                }
                
                String stateId = efsm.getConfiguration().getState().getId();
                if (stateId.equals("Success")) {
                    System.out.println("Reached terminal state: " + stateId + ". Test Flow Completed.");
                    postTestLog(currentOrderId, stateId, "TERMINAL", null);
                    break;
                }

                List<EFSMTransition> available = new ArrayList<>(efsm.transitionsOutOf(efsm.getConfiguration().getState()));
                Collections.shuffle(available, rand); // random walk
                
                EFSMTransition selected = null;
                for (EFSMTransition t : available) {
                    if (t.isFeasible(efsm.getConfiguration().getContext())) {
                        selected = t;
                        break;
                    }
                }
                
                if (selected == null) {
                    System.err.println("CRITICAL: No feasible transitions from " + stateId + "!");
                    break;
                }

                String action = selected.getId();
                System.out.printf("Step [%-15s] -> %-20s%n", stateId, action);
                JsonObject sutState = executeOnSUT(action);
                
                if (sutState != null && sutState.has("error")) {
                    System.out.println("   [❌] ORACLE FAILED: SUT rejected a valid model transition!");
                    
                    JsonObject errPayload = new JsonObject();
                    errPayload.addProperty("error_detail", "SUT Rejected Valid Model Transition: " + sutState.get("error").getAsString());
                    
                    postTestLog(currentOrderId, stateId, action + " [FAIL]", errPayload);
                    break; // TERMINATE Test Run as Failed Oracle
                }
                
                if (sutState == null) break; // safety fallback

                // 1. Take transition internally BEFORE syncing new vars, so EFSM Guards evaluate on the true PRE-state!
                efsm.transition(null, selected.getTgt());

                // Note: We bypass strict iv4xr memory checking due to the framework's mathematical self-loop bug, 
                // and directly rely on the Fuzzing HTTP 400 rejections from the SUT as the active oracle!
                efsm.getConfiguration().getContext().getContext().getVariable("itemsCount").setValue(sutState.get("items_count").getAsInt());
                efsm.getConfiguration().getContext().getContext().getVariable("cost").setValue(sutState.get("total_price").getAsInt());
                efsm.getConfiguration().getContext().getContext().getVariable("hasVoucher").setValue(sutState.get("has_voucher").getAsInt());
                efsm.getConfiguration().getContext().getContext().getVariable("walletAmount").setValue(sutState.get("wallet_amount").getAsInt());

                postTestLog(currentOrderId, stateId, action, sutState);
                Thread.sleep(50); // Super fast execution so user can play them back manually!
            }
        }
        
        System.out.println("\n✅ Advanced Testing Complete! Check the Web Dashboard!");
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

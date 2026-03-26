# EvoMBT E-Commerce Order Testing

Model-Based Testing (MBT) pipeline for a Python FastAPI e-commerce backend, using the [iv4xr-mbt](https://github.com/iv4xr-project/EvoMBT) Java library with EvoSuite MOSA test generation and a live HTML/SSE dashboard.

## Architecture

| Component | Tech | Role |
|-----------|------|------|
| **SUT Backend** | Python / FastAPI / SQLite | E-commerce Order API under test |
| **EFSM Model** | Java / iv4xr-mbt | 8-state Extended Finite State Machine for the order lifecycle |
| **Test Generator** | EvoSuite MOSA (iv4xr-mbt) | Automatically generates abstract test paths from the EFSM model |
| **Test Runner** | Java (`OrderRunner.java`) | Concretises abstract paths and executes them against the SUT; implements a Strict Oracle |
| **Dashboard** | HTML / JS / SSE / vis.js | Real-time interactive visualisation of EFSM traversal and test results |

## EFSM States

```
Empty → N_Items ↔ N_Items_Voucher ← Voucher_Applied
                          ↓
                       Checkout
                       /      \
              Pending_Pay    Success (wallet)
              /       \
           Fail       Success (external)
```

Reversible arcs: `deleteItem`, `cancelCheckout`, `cancelPayment`, `retryPayment`.

## Prerequisites

- **Python 3.9+** with `pip`
- **Java JDK 11+**
- **Apache Maven 3.8+**

> In VS Code, `JAVA_HOME` and `MAVEN_HOME` are auto-detected if set in your environment.

## Running the Application

### 1. Start the Backend & Dashboard

**Option A – VS Code (recommended):**

Two tasks are preconfigured in `.vscode/tasks.json`:

| Task | How to run | What it does |
|------|-----------|-------------|
| **Start FastAPI Backend** | `Ctrl+Shift+P` → *Tasks: Run Task* → select it | Starts uvicorn in `backend/`, opens a dedicated terminal panel |
| **Run EvoMBT Pipeline** | `Ctrl+Shift+B` (default build shortcut) | Runs `run_pipeline.bat` - compiles Java, generates tests, executes them |

Start the backend first, open [http://localhost:8000/dashboard](http://localhost:8000/dashboard), then run the pipeline task.


**Option B – Terminal:**
```bash
cd backend
pip install -r requirements.txt          # first time only
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```
Then open [http://localhost:8000/dashboard](http://localhost:8000/dashboard).

### 2. Run the Full Test Pipeline

Keep the backend running, then in a **new terminal** inside `evombt-tests/`:

```bash
cd evombt-tests
.\run_pipeline.bat
```

This runs three phases automatically:

| Phase | What happens |
|-------|-------------|
| **0 – Compile** | `mvn compile` builds the Java project |
| **1 – Generate** | EvoSuite MOSA generates abstract test paths from the EFSM model and writes them to `mbt-files/tests/…/test_N.txt` |
| **2 – Execute** | `OrderRunner` concretises each path, calls the SUT REST API, applies the Strict Oracle, and streams results to the dashboard |

Watch the dashboard live - completed tests appear as navigable flows in the **SUT Order Snapshots** panel; tests where the Oracle detected a divergence are highlighted in red.

## Dashboard Features

- **Interactive EFSM graph** – current state highlighted in real time
- **Playback controls** – step forwards / backwards through any test flow
- **Internal Variables panel** – live SUT state (items, cost, wallet, voucher)
- **Action Trajectory Log** – full step-by-step log with Oracle error details
- **SUT Order Snapshots** – all test runs; red = Oracle failure, click to visualise on graph
- **Refresh-safe** – test log history is cached server-side and restored on page reload

## Strict Oracle

After each transition the runner compares the model's expected outcome against the SUT response:

- **SUT rejects a model-valid action** → `[FAIL]` logged, test terminates
- **SUT accepts a model-invalid action** → backend guard catches it and returns HTTP 400

A 20% random failure is injected into `pay_with_wallet` to demonstrate Oracle detection (see `backend/main.py` → `pay_with_wallet`). Remove the `random.random()` block to restore normal behaviour.

## Project Structure

```
evombt-testing/
├── backend/
│   ├── main.py             # FastAPI SUT + SSE telemetry server
│   └── dashboard.html      # Real-time dashboard
├── evombt-tests/
│   ├── src/main/java/org/evombt/
│   │   ├── OrderEFSM.java  # EFSM model definition
│   │   └── OrderRunner.java # Test concretisation & Oracle
│   ├── mbt-files/tests/    # Generated abstract test paths (auto-created)
│   ├── lib/EvoMBT.jar      # iv4xr-mbt library
│   ├── pom.xml
│   └── run_pipeline.bat    # ← entry point for running tests
└── README.md
```

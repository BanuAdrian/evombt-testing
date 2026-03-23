from fastapi import FastAPI, Depends, HTTPException
from fastapi.responses import HTMLResponse, StreamingResponse
from sqlalchemy.orm import Session
from pydantic import BaseModel
from typing import List, Optional
import asyncio
import json
import os
import random

from database import SessionLocal, engine, Base, Order

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
Base.metadata.create_all(bind=engine)
app = FastAPI(title="E-Commerce Order API")

# ---- SSE infrastructure ----------------------------------------
_queues: list[asyncio.Queue] = []
_log_queues: list[asyncio.Queue] = []
_loop: asyncio.AbstractEventLoop = None

@app.on_event("startup")
async def startup():
    global _loop
    _loop = asyncio.get_event_loop()

async def _broadcast(queues: list, data: dict):
    dead = []
    for q in queues:
        try:
            q.put_nowait(data)
        except asyncio.QueueFull:
            dead.append(q)
    for q in dead:
        queues.remove(q)

def broadcast_sync(queues: list, data: dict):
    if _loop and _loop.is_running():
        asyncio.run_coroutine_threadsafe(_broadcast(queues, data), _loop)

# ---- DB dependency --------------------------------------------
def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

# ---- Pydantic schemas -----------------------------------------
class OrderResponse(BaseModel):
    id: int
    status: str
    items_count: int
    has_voucher: int
    wallet_amount: int
    total_price: int
    model_config = {"from_attributes": True}

# ---- CRUD routes -----------------------------------------------

def notify_order(order: Order, event_type: str):
    broadcast_sync(_queues, {
        "type": event_type, 
        "order": {"id": order.id, "status": order.status, "items_count": order.items_count, "total_price": order.total_price, "has_voucher": order.has_voucher, "wallet_amount": order.wallet_amount}
    })

def recalculate_total(order: Order):
    base_cost = order.items_count * 10
    discount = 5 if order.has_voucher == 1 else 0
    order.total_price = max(0, base_cost - discount)

@app.post("/orders", response_model=OrderResponse, status_code=201)
def create_order(db: Session = Depends(get_db)):
    db_order = Order(status="Empty", items_count=0, has_voucher=0, wallet_amount=random.randint(10, 100), total_price=0)
    db.add(db_order)
    db.commit()
    db.refresh(db_order)
    notify_order(db_order, "created")
    return db_order

@app.get("/orders", response_model=List[OrderResponse])
def get_orders(db: Session = Depends(get_db)):
    return db.query(Order).all()

@app.post("/orders/{order_id}/items", response_model=OrderResponse)
def add_item(order_id: int, db: Session = Depends(get_db)):
    db_order = db.query(Order).filter(Order.id == order_id).first()
    if not db_order or db_order.status not in ["Empty", "N_Items", "Voucher_Applied", "N_Items_Voucher"]:
        raise HTTPException(status_code=400, detail="Cannot add item in this state")
    
    db_order.items_count += 1
    recalculate_total(db_order)
    
    # State mutation logic
    if db_order.status == "Empty":
        db_order.status = "N_Items"
    elif db_order.status == "Voucher_Applied":
        db_order.status = "N_Items_Voucher"
        
    db.commit()
    db.refresh(db_order)
    notify_order(db_order, "updated")
    return db_order

@app.delete("/orders/{order_id}/items", response_model=OrderResponse)
def delete_item(order_id: int, db: Session = Depends(get_db)):
    db_order = db.query(Order).filter(Order.id == order_id).first()
    if not db_order or db_order.status not in ["N_Items", "N_Items_Voucher"]:
        raise HTTPException(status_code=400, detail="No items to delete in this state")
    
    db_order.items_count = max(0, db_order.items_count - 1)
    recalculate_total(db_order)
    
    if db_order.items_count == 0:
        if db_order.has_voucher == 1:
            db_order.status = "Voucher_Applied"
        else:
            db_order.status = "Empty"

    db.commit()
    db.refresh(db_order)
    notify_order(db_order, "updated")
    return db_order

@app.post("/orders/{order_id}/voucher", response_model=OrderResponse)
def apply_voucher(order_id: int, db: Session = Depends(get_db)):
    db_order = db.query(Order).filter(Order.id == order_id).first()
    if not db_order or db_order.status not in ["Empty", "N_Items"]:
        raise HTTPException(status_code=400, detail="Voucher cannot be applied now (already applied or past checkout)")
    
    db_order.has_voucher = 1
    recalculate_total(db_order)
    
    # State mutation
    if db_order.status == "Empty":
        db_order.status = "Voucher_Applied"
    elif db_order.status == "N_Items":
        db_order.status = "N_Items_Voucher"
        
    db.commit()
    db.refresh(db_order)
    notify_order(db_order, "updated")
    return db_order

@app.put("/orders/{order_id}/checkout", response_model=OrderResponse)
def checkout(order_id: int, db: Session = Depends(get_db)):
    db_order = db.query(Order).filter(Order.id == order_id).first()
    if not db_order or db_order.status not in ["N_Items", "N_Items_Voucher"]:
        raise HTTPException(status_code=400, detail="Cannot checkout an empty cart or already checked out cart")
    
    db_order.status = "Checkout"
    db.commit()
    db.refresh(db_order)
    notify_order(db_order, "updated")
    return db_order

@app.put("/orders/{order_id}/checkout/cancel", response_model=OrderResponse)
def cancel_checkout(order_id: int, db: Session = Depends(get_db)):
    db_order = db.query(Order).filter(Order.id == order_id).first()
    if not db_order or db_order.status != "Checkout":
        raise HTTPException(status_code=400, detail="Cannot cancel checkout if not in Checkout state")
        
    if db_order.has_voucher == 1:
        db_order.status = "N_Items_Voucher"
    else:
        db_order.status = "N_Items"
        
    db.commit()
    db.refresh(db_order)
    notify_order(db_order, "updated")
    return db_order

@app.put("/orders/{order_id}/pay/wallet", response_model=OrderResponse)
def pay_with_wallet(order_id: int, db: Session = Depends(get_db)):
    import random
    if random.random() < 0.20:
        raise HTTPException(status_code=400, detail="Wallet Service is down. Random bug injected!")

    db_order = db.query(Order).filter(Order.id == order_id).first()
    if not db_order or db_order.status != "Checkout":
        raise HTTPException(status_code=400, detail="Not in checkout")
        
    if db_order.wallet_amount < db_order.total_price:
        raise HTTPException(status_code=400, detail="Insufficient wallet funds")
        
    db_order.wallet_amount -= db_order.total_price
    db_order.status = "Success"
    db.commit()
    db.refresh(db_order)
    notify_order(db_order, "updated")
    return db_order

@app.put("/orders/{order_id}/pay/external", response_model=OrderResponse)
def pay_external(order_id: int, db: Session = Depends(get_db)):
    db_order = db.query(Order).filter(Order.id == order_id).first()
    if not db_order or db_order.status != "Checkout":
        raise HTTPException(status_code=400, detail="Not in checkout")
        
    if db_order.wallet_amount >= db_order.total_price:
        raise HTTPException(status_code=400, detail="You have enough funds, use pay/wallet instead")
        
    db_order.status = "Pending_Pay"
    db.commit()
    db.refresh(db_order)
    notify_order(db_order, "updated")
    return db_order

@app.put("/orders/{order_id}/pay/external/success", response_model=OrderResponse)
def external_pay_success(order_id: int, db: Session = Depends(get_db)):
    db_order = db.query(Order).filter(Order.id == order_id).first()
    if not db_order or db_order.status != "Pending_Pay":
        raise HTTPException(status_code=400, detail="Not waiting for external payment")
        
    db_order.status = "Success"
    db.commit()
    db.refresh(db_order)
    notify_order(db_order, "updated")
    return db_order

@app.put("/orders/{order_id}/pay/external/fail", response_model=OrderResponse)
def external_pay_fail(order_id: int, db: Session = Depends(get_db)):
    db_order = db.query(Order).filter(Order.id == order_id).first()
    if not db_order or db_order.status != "Pending_Pay":
        raise HTTPException(status_code=400, detail="Not waiting for external payment")
        
    db_order.status = "Fail"
    db.commit()
    db.refresh(db_order)
    notify_order(db_order, "updated")
    return db_order

@app.put("/orders/{order_id}/pay/external/cancel", response_model=OrderResponse)
def external_pay_cancel(order_id: int, db: Session = Depends(get_db)):
    db_order = db.query(Order).filter(Order.id == order_id).first()
    if not db_order or db_order.status != "Pending_Pay":
        raise HTTPException(status_code=400, detail="Not waiting for external payment")
        
    db_order.status = "Checkout"
    db.commit()
    db.refresh(db_order)
    notify_order(db_order, "updated")
    return db_order
    
@app.put("/orders/{order_id}/pay/external/retry", response_model=OrderResponse)
def external_pay_retry(order_id: int, db: Session = Depends(get_db)):
    db_order = db.query(Order).filter(Order.id == order_id).first()
    if not db_order or db_order.status != "Fail":
        raise HTTPException(status_code=400, detail="Can only retry from Fail state")
        
    db_order.status = "Pending_Pay"
    db.commit()
    db.refresh(db_order)
    notify_order(db_order, "updated")
    return db_order

@app.delete("/orders", status_code=204)
def clear_orders(db: Session = Depends(get_db)):
    db.query(Order).delete()
    db.commit()
    _cached_test_logs.clear()
    broadcast_sync(_queues, {"type": "cleared"})
    return None

# ---- Test log endpoint (EvoMBT runner posts steps here) --------
_cached_test_logs = []

@app.post("/test-log")
async def post_test_log(entry: dict):
    _cached_test_logs.append(entry)
    await _broadcast(_log_queues, entry)
    return {"ok": True}

@app.get("/test-log/history")
async def get_test_log_history():
    return _cached_test_logs

# ---- SSE streams -----------------------------------------------
@app.get("/events/orders")
async def order_events():
    async def stream():
        q = asyncio.Queue(maxsize=100)
        _queues.append(q)
        try:
            yield "data: {\"type\":\"connected\"}\n\n"
            while True:
                try:
                    data = await asyncio.wait_for(q.get(), timeout=20)
                    yield f"data: {json.dumps(data)}\n\n"
                except asyncio.TimeoutError:
                    yield "data: {\"type\":\"ping\"}\n\n"
        finally:
            if q in _queues:
                _queues.remove(q)
    return StreamingResponse(stream(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

@app.get("/events/log")
async def log_events():
    async def stream():
        q = asyncio.Queue(maxsize=100)
        _log_queues.append(q)
        try:
            yield "data: {\"type\":\"connected\"}\n\n"
            while True:
                try:
                    data = await asyncio.wait_for(q.get(), timeout=20)
                    yield f"data: {json.dumps(data)}\n\n"
                except asyncio.TimeoutError:
                    yield "data: {\"type\":\"ping\"}\n\n"
        finally:
            if q in _log_queues:
                _log_queues.remove(q)
    return StreamingResponse(stream(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

# ---- HTML Dashboard -------------------------------------------
@app.get("/dashboard", response_class=HTMLResponse)
@app.get("/", response_class=HTMLResponse)
def dashboard():
    with open(os.path.join(BASE_DIR, "dashboard.html"), encoding="utf-8") as f:
        return f.read()

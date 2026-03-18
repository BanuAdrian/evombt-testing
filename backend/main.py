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
    total_price: int
    model_config = {"from_attributes": True}

# ---- CRUD routes -----------------------------------------------

def notify_order(order: Order, event_type: str):
    broadcast_sync(_queues, {
        "type": event_type, 
        "order": {"id": order.id, "status": order.status, "items_count": order.items_count, "total_price": order.total_price}
    })

@app.post("/orders", response_model=OrderResponse, status_code=201)
def create_order(db: Session = Depends(get_db)):
    db_order = Order(status="CART", items_count=0, total_price=0)
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
    if not db_order or db_order.status != "CART":
        raise HTTPException(status_code=400, detail="Invalid order or not in CART")
    db_order.items_count += 1
    db_order.total_price += random.randint(10, 50)
    db.commit()
    db.refresh(db_order)
    notify_order(db_order, "updated")
    return db_order

@app.put("/orders/{order_id}/checkout", response_model=OrderResponse)
def checkout(order_id: int, db: Session = Depends(get_db)):
    db_order = db.query(Order).filter(Order.id == order_id).first()
    if not db_order or db_order.status != "CART" or db_order.items_count == 0:
        raise HTTPException(status_code=400, detail="Invalid order or empty cart")
    db_order.status = "PENDING_PAYMENT"
    db.commit()
    db.refresh(db_order)
    notify_order(db_order, "updated")
    return db_order

@app.put("/orders/{order_id}/pay", response_model=OrderResponse)
def pay(order_id: int, db: Session = Depends(get_db)):
    db_order = db.query(Order).filter(Order.id == order_id).first()
    if not db_order or db_order.status != "PENDING_PAYMENT":
        raise HTTPException(status_code=400, detail="Invalid order or not pending payment")
    db_order.status = "PAID"
    db.commit()
    db.refresh(db_order)
    notify_order(db_order, "updated")
    return db_order

@app.put("/orders/{order_id}/ship", response_model=OrderResponse)
def ship(order_id: int, db: Session = Depends(get_db)):
    db_order = db.query(Order).filter(Order.id == order_id).first()
    if not db_order or db_order.status != "PAID":
        raise HTTPException(status_code=400, detail="Invalid order or not paid")
    db_order.status = "SHIPPED"
    db.commit()
    db.refresh(db_order)
    notify_order(db_order, "updated")
    return db_order

@app.put("/orders/{order_id}/cancel", response_model=OrderResponse)
def cancel(order_id: int, db: Session = Depends(get_db)):
    db_order = db.query(Order).filter(Order.id == order_id).first()
    if not db_order or db_order.status in ["SHIPPED", "CANCELLED"]:
        raise HTTPException(status_code=400, detail="Cannot cancel shipped or already cancelled order")
    db_order.status = "CANCELLED"
    db.commit()
    db.refresh(db_order)
    notify_order(db_order, "updated")
    return db_order

@app.delete("/orders", status_code=204)
def clear_orders(db: Session = Depends(get_db)):
    db.query(Order).delete()
    db.commit()
    broadcast_sync(_queues, {"type": "cleared"})
    return None

# ---- Test log endpoint (EvoMBT runner posts steps here) --------
@app.post("/test-log")
async def post_test_log(entry: dict):
    await _broadcast(_log_queues, entry)
    return {"ok": True}

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

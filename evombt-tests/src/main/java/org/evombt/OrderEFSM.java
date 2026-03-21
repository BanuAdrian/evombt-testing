package org.evombt;

import eu.fbk.iv4xr.mbt.efsm.*;
import eu.fbk.iv4xr.mbt.efsm.exp.*;
import eu.fbk.iv4xr.mbt.efsm.exp.integer.*;
import eu.fbk.iv4xr.mbt.efsm.exp.bool.*;

public class OrderEFSM {
    private EFSM efsm;

    public OrderEFSM() {
        // Variables
        Var<Integer> itemsCount = new Var<Integer>("itemsCount", 0);
        Var<Integer> hasVoucher = new Var<Integer>("hasVoucher", 0);
        Var<Integer> walletAmount = new Var<Integer>("walletAmount", 0);
        Var<Integer> cost = new Var<Integer>("cost", 0);
        EFSMContext context = new EFSMContext(itemsCount, hasVoucher, walletAmount, cost);

        // States
        EFSMState s_Empty = new EFSMState("Empty");
        EFSMState s_N_Items = new EFSMState("N_Items");
        EFSMState s_Voucher_Applied = new EFSMState("Voucher_Applied");
        EFSMState s_N_Items_Voucher = new EFSMState("N_Items_Voucher");
        EFSMState s_Checkout = new EFSMState("Checkout");
        EFSMState s_Pending_Pay = new EFSMState("Pending_Pay");
        EFSMState s_Success = new EFSMState("Success");
        EFSMState s_Fail = new EFSMState("Fail");

        EFSMBuilder builder = new EFSMBuilder(EFSM.class);

        // Operations (AST)
        Assign<Integer> incItems = new Assign<>(itemsCount, new IntSum(itemsCount, new Const<Integer>(1)));
        Assign<Integer> incCost = new Assign<>(cost, new IntSum(cost, new Const<Integer>(10)));
        EFSMOperation addItemOp = new EFSMOperation(incItems, incCost);
        
        Assign<Integer> setVoucher = new Assign<>(hasVoucher, new Const<Integer>(1));
        Assign<Integer> decCost = new Assign<>(cost, new IntSubt(cost, new Const<Integer>(5)));
        EFSMOperation applyVoucherOp = new EFSMOperation(setVoucher, decCost);

        // Guards (AST) -> walletAmount >= cost
        BoolOr canPayW_OR = new BoolOr(
            new IntGreat(walletAmount, cost),
            new IntEq(walletAmount, cost)
        );
        EFSMGuard canPayWithWallet = new EFSMGuard(canPayW_OR);

        // walletAmount < cost
        IntLess cannotPayW = new IntLess(walletAmount, cost);
        EFSMGuard mustPayExternal = new EFSMGuard(cannotPayW);
        
        // ensure we only checkout if itemsCount > 0
        IntGreat hasItemsCond = new IntGreat(itemsCount, new Const<Integer>(0));
        EFSMGuard hasItemsGuard = new EFSMGuard(hasItemsCond);

        // Deletion and Reversion Guards + Math operations
        IntEq isOne = new IntEq(itemsCount, new Const<Integer>(1));
        IntGreat moreThanOne = new IntGreat(itemsCount, new Const<Integer>(1));
        IntEq hasVoucherCond = new IntEq(hasVoucher, new Const<Integer>(1));
        IntEq noVoucherCond = new IntEq(hasVoucher, new Const<Integer>(0));

        EFSMGuard guardIsOne = new EFSMGuard(isOne);
        EFSMGuard guardMoreThanOne = new EFSMGuard(moreThanOne);
        EFSMGuard guardHasVoucher = new EFSMGuard(hasVoucherCond);
        EFSMGuard guardNoVoucher = new EFSMGuard(noVoucherCond);

        Assign<Integer> setZeroCost = new Assign<>(cost, new Const<Integer>(0));
        Assign<Integer> setZeroItems = new Assign<>(itemsCount, new Const<Integer>(0));
        EFSMOperation clearCartOp = new EFSMOperation(setZeroItems, setZeroCost);

        Assign<Integer> decItems = new Assign<>(itemsCount, new IntSubt(itemsCount, new Const<Integer>(1)));
        Assign<Integer> sub10 = new Assign<>(cost, new IntSubt(cost, new Const<Integer>(10)));
        EFSMOperation deleteItemOp = new EFSMOperation(decItems, sub10);

        // Transitions using builder
        builder.withTransition(s_Empty, s_N_Items, new EFSMTransition("t_addItem_1", addItemOp, null, null, null));
        builder.withTransition(s_Empty, s_Voucher_Applied, new EFSMTransition("t_applyVoucher_1", applyVoucherOp, null, null, null));
        
        builder.withTransition(s_N_Items, s_N_Items, new EFSMTransition("t_addItem_2", addItemOp, null, null, null));
        builder.withTransition(s_N_Items, s_N_Items_Voucher, new EFSMTransition("t_applyVoucher_2", applyVoucherOp, null, null, null));
        builder.withTransition(s_N_Items, s_Checkout, new EFSMTransition("t_checkout_1", null, hasItemsGuard, null, null));
        
        builder.withTransition(s_Voucher_Applied, s_N_Items_Voucher, new EFSMTransition("t_addItem_3", addItemOp, null, null, null));
        
        builder.withTransition(s_N_Items_Voucher, s_N_Items_Voucher, new EFSMTransition("t_addItem_4", addItemOp, null, null, null));
        builder.withTransition(s_N_Items_Voucher, s_Checkout, new EFSMTransition("t_checkout_2", null, hasItemsGuard, null, null));
        
        // Complex routing transitions based on Wallet math guards
        builder.withTransition(s_Checkout, s_Success, new EFSMTransition("t_payWithWallet_1", null, canPayWithWallet, null, null));
        builder.withTransition(s_Checkout, s_Pending_Pay, new EFSMTransition("t_payExternal_1", null, mustPayExternal, null, null));
        
        // External payment transitions
        builder.withTransition(s_Pending_Pay, s_Success, new EFSMTransition("t_externalPaySuccess_1"));
        builder.withTransition(s_Pending_Pay, s_Fail, new EFSMTransition("t_externalPayFail_1"));

        // NEW REVERSIBLE ARCS
        builder.withTransition(s_N_Items, s_Empty, new EFSMTransition("t_deleteItem_1", clearCartOp, guardIsOne, null, null));
        builder.withTransition(s_N_Items_Voucher, s_Voucher_Applied, new EFSMTransition("t_deleteItem_2", clearCartOp, guardIsOne, null, null));
        builder.withTransition(s_N_Items, s_N_Items, new EFSMTransition("t_deleteItem_3", deleteItemOp, guardMoreThanOne, null, null));
        builder.withTransition(s_N_Items_Voucher, s_N_Items_Voucher, new EFSMTransition("t_deleteItem_4", deleteItemOp, guardMoreThanOne, null, null));
        
        builder.withTransition(s_Checkout, s_N_Items, new EFSMTransition("t_cancelCheckout_1", null, guardNoVoucher, null, null));
        builder.withTransition(s_Checkout, s_N_Items_Voucher, new EFSMTransition("t_cancelCheckout_2", null, guardHasVoucher, null, null));
        
        builder.withTransition(s_Pending_Pay, s_Checkout, new EFSMTransition("t_cancelPayment_1"));
        builder.withTransition(s_Fail, s_Pending_Pay, new EFSMTransition("t_retryPayment_1"));


        efsm = builder.build(s_Empty, context, new OrderParameterGenerator());
    }

    public EFSM getModel() { return efsm; }
}
package org.evombt;

import eu.fbk.iv4xr.mbt.efsm.*;
import eu.fbk.iv4xr.mbt.efsm.exp.*;
import eu.fbk.iv4xr.mbt.efsm.exp.integer.*;
import org.jgrapht.graph.DirectedPseudograph;

/**
 * Clean & Practical EFSM for an E-Commerce Order API
 */
public class OrderEFSM {

    private EFSM efsm;

    public OrderEFSM() {
        // 1. Variabile de Context (Memoria modelului)
        Var<Integer> itemsCount = new Var<>("itemsCount", 0);
        EFSMContext context = new EFSMContext(itemsCount);

        // 2. Stările Comenzii
        EFSMState cart     = new EFSMState("CART");
        EFSMState pending  = new EFSMState("PENDING_PAYMENT");
        EFSMState paid     = new EFSMState("PAID");
        EFSMState shipped  = new EFSMState("SHIPPED");
        EFSMState canceled = new EFSMState("CANCELLED");

        // 3. Operații pe variabile
        Assign<Integer> addOneItem = new Assign<>(itemsCount, new IntSum(itemsCount, new Const<>(1)));
        Assign<Integer> resetItems = new Assign<>(itemsCount, new Const<>(0));

        // 4. Gărzi (Condiții pentru tranziții)
        // Guard: itemsCount > 0
        EFSMGuard hasItems = new EFSMGuard(new IntGreat(itemsCount, new Const<>(0)));

        // 5. Construirea Grafului EFSM cu EFSMBuilder
        EFSMBuilder builder = new EFSMBuilder(EFSM.class);

        // TRANZIȚII

        // START -> adăugăm iteme în coș (repetat)
        builder.withTransition(cart, cart, new EFSMTransition(
            "t_addItem", new EFSMOperation(addOneItem), null, null, null
        ));

        // CART -> PENDING (checkout posibil doar dacă avem iteme)
        builder.withTransition(cart, pending, new EFSMTransition(
            "t_checkout", null, hasItems, null, null
        ));

        // PENDING -> PAID (plata a fost efectuată)
        builder.withTransition(pending, paid, new EFSMTransition(
            "t_pay", null, null, null, null
        ));

        // PAID -> SHIPPED (comanda a fost trimisă)
        builder.withTransition(paid, shipped, new EFSMTransition(
            "t_ship", null, null, null, null
        ));

        // ANULĂRI (din CART sau PENDING)
        builder.withTransition(cart, canceled, new EFSMTransition(
            "t_cancelCart", null, null, null, null
        ));
        builder.withTransition(pending, canceled, new EFSMTransition(
            "t_cancelPending", null, null, null, null
        ));

        // CICLU NOU: Începem o comandă nouă dacă cea veche s-a terminat (shipped/cancelled)
        builder.withTransition(shipped, cart, new EFSMTransition(
            "t_newOrderFromShipped", new EFSMOperation(resetItems), null, null, null
        ));
        builder.withTransition(canceled, cart, new EFSMTransition(
            "t_newOrderFromCancelled", new EFSMOperation(resetItems), null, null, null
        ));

        // Crearea modelului final EvoMBT
        efsm = builder.build(cart, context, new OrderParameterGenerator());
    }

    public EFSM getModel() {
        return efsm;
    }
}

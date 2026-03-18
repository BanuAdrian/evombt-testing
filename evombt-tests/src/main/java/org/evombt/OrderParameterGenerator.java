package org.evombt;

import eu.fbk.iv4xr.mbt.efsm.EFSMParameterGenerator;
import eu.fbk.iv4xr.mbt.efsm.exp.Const;

import java.io.Serializable;

import eu.fbk.iv4xr.mbt.efsm.EFSMParameter;

public class OrderParameterGenerator extends EFSMParameterGenerator {

    /**
     * Required by EvoMBT interface, even if not strictly used in our simple
     * EFSM variables. Provides a random value when evaluating context fields.
     */
    @Override
    public EFSMParameter getRandom() {
        return new EFSMParameter(new eu.fbk.iv4xr.mbt.efsm.exp.Var<Integer>("dummy", 1));
    }
}

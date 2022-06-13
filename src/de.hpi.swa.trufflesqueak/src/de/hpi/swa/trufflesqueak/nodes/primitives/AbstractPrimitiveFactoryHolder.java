/*
 * Copyright (c) 2017-2022 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2021-2022 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.primitives;

import java.util.Collections;
import java.util.List;

import com.oracle.truffle.api.dsl.NodeFactory;

public abstract class AbstractPrimitiveFactoryHolder {
    public abstract List<? extends NodeFactory<? extends AbstractPrimitiveNode>> getFactories();

    public List<Class<? extends AbstractSingletonPrimitiveNode>> getSingletonPrimitives() {
        return Collections.emptyList();
    }
}

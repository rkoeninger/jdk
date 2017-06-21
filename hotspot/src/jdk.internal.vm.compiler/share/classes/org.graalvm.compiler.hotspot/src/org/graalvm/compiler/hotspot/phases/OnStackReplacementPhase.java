/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.hotspot.phases;

import static org.graalvm.compiler.phases.common.DeadCodeEliminationPhase.Optionality.Required;

import org.graalvm.compiler.core.common.PermanentBailoutException;
import org.graalvm.compiler.core.common.cfg.Loop;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.debug.Debug;
import org.graalvm.compiler.debug.DebugCounter;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.iterators.NodeIterable;
import org.graalvm.compiler.loop.LoopsData;
import org.graalvm.compiler.loop.phases.LoopTransformations;
import org.graalvm.compiler.nodeinfo.InputType;
import org.graalvm.compiler.nodeinfo.Verbosity;
import org.graalvm.compiler.nodes.AbstractBeginNode;
import org.graalvm.compiler.nodes.AbstractLocalNode;
import org.graalvm.compiler.nodes.EntryMarkerNode;
import org.graalvm.compiler.nodes.EntryProxyNode;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.LoopBeginNode;
import org.graalvm.compiler.nodes.ParameterNode;
import org.graalvm.compiler.nodes.StartNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.cfg.Block;
import org.graalvm.compiler.nodes.extended.OSRLocalNode;
import org.graalvm.compiler.nodes.extended.OSRLockNode;
import org.graalvm.compiler.nodes.extended.OSRMonitorEnterNode;
import org.graalvm.compiler.nodes.extended.OSRStartNode;
import org.graalvm.compiler.nodes.java.AccessMonitorNode;
import org.graalvm.compiler.nodes.java.MonitorEnterNode;
import org.graalvm.compiler.nodes.java.MonitorExitNode;
import org.graalvm.compiler.nodes.java.MonitorIdNode;
import org.graalvm.compiler.nodes.util.GraphUtil;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionType;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.Phase;
import org.graalvm.compiler.phases.common.DeadCodeEliminationPhase;

import jdk.vm.ci.runtime.JVMCICompiler;

public class OnStackReplacementPhase extends Phase {

    public static class Options {
        // @formatter:off
        @Option(help = "Deoptimize OSR compiled code when the OSR entry loop is finished " +
                       "if there is no mature profile available for the rest of the method.", type = OptionType.Debug)
        public static final OptionKey<Boolean> DeoptAfterOSR = new OptionKey<>(true);
        @Option(help = "Support OSR compilations with locks. If DeoptAfterOSR is true we can per definition not have " +
                       "unbalaced enter/extis mappings. If DeoptAfterOSR is false insert artificial monitor enters after " +
                       "the OSRStart to have balanced enter/exits in the graph.", type = OptionType.Debug)
        public static final OptionKey<Boolean> SupportOSRWithLocks = new OptionKey<>(true);
        // @formatter:on
    }

    private static final DebugCounter OsrWithLocksCount = Debug.counter("OSRWithLocks");

    private static boolean supportOSRWithLocks(OptionValues options) {
        return Options.SupportOSRWithLocks.getValue(options);
    }

    @Override
    protected void run(StructuredGraph graph) {
        if (graph.getEntryBCI() == JVMCICompiler.INVOCATION_ENTRY_BCI) {
            // This happens during inlining in a OSR method, because the same phase plan will be
            // used.
            assert graph.getNodes(EntryMarkerNode.TYPE).isEmpty();
            return;
        }
        Debug.dump(Debug.DETAILED_LEVEL, graph, "OnStackReplacement initial at bci %d", graph.getEntryBCI());

        EntryMarkerNode osr;
        int maxIterations = -1;
        int iterations = 0;

        final EntryMarkerNode originalOSRNode = getEntryMarker(graph);
        final LoopBeginNode originalOSRLoop = osrLoop(originalOSRNode);
        final boolean currentOSRWithLocks = osrWithLocks(originalOSRNode);

        if (originalOSRLoop == null) {
            /*
             * OSR with Locks: We do not have an OSR loop for the original OSR bci. Therefore we
             * cannot decide where to deopt and which framestate will be used. In the worst case the
             * framestate of the OSR entry would be used.
             */
            throw new PermanentBailoutException("OSR compilation without OSR entry loop.");
        }

        if (!supportOSRWithLocks(graph.getOptions()) && currentOSRWithLocks) {
            throw new PermanentBailoutException("OSR with locks disabled.");
        }

        do {
            osr = getEntryMarker(graph);
            LoopsData loops = new LoopsData(graph);
            // Find the loop that contains the EntryMarker
            Loop<Block> l = loops.getCFG().getNodeToBlock().get(osr).getLoop();
            if (l == null) {
                break;
            }

            iterations++;
            if (maxIterations == -1) {
                maxIterations = l.getDepth();
            } else if (iterations > maxIterations) {
                throw GraalError.shouldNotReachHere();
            }
            // Peel the outermost loop first
            while (l.getParent() != null) {
                l = l.getParent();
            }

            LoopTransformations.peel(loops.loop(l));
            osr.replaceAtUsages(InputType.Guard, AbstractBeginNode.prevBegin((FixedNode) osr.predecessor()));
            for (Node usage : osr.usages().snapshot()) {
                EntryProxyNode proxy = (EntryProxyNode) usage;
                proxy.replaceAndDelete(proxy.value());
            }
            GraphUtil.removeFixedWithUnusedInputs(osr);
            Debug.dump(Debug.DETAILED_LEVEL, graph, "OnStackReplacement loop peeling result");
        } while (true);

        FrameState osrState = osr.stateAfter();
        osr.setStateAfter(null);
        OSRStartNode osrStart = graph.add(new OSRStartNode());
        StartNode start = graph.start();
        FixedNode next = osr.next();
        osr.setNext(null);
        osrStart.setNext(next);
        graph.setStart(osrStart);
        osrStart.setStateAfter(osrState);

        Debug.dump(Debug.DETAILED_LEVEL, graph, "OnStackReplacement after setting OSR start");
        final int localsSize = osrState.localsSize();
        final int locksSize = osrState.locksSize();

        for (int i = 0; i < localsSize + locksSize; i++) {
            ValueNode value = null;
            if (i >= localsSize) {
                value = osrState.lockAt(i - localsSize);
            } else {
                value = osrState.localAt(i);
            }
            if (value instanceof EntryProxyNode) {
                EntryProxyNode proxy = (EntryProxyNode) value;
                /*
                 * we need to drop the stamp since the types we see during OSR may be too precise
                 * (if a branch was not parsed for example).
                 */
                Stamp s = proxy.stamp().unrestricted();
                AbstractLocalNode osrLocal = null;
                if (i >= localsSize) {
                    osrLocal = graph.addOrUnique(new OSRLockNode(i - localsSize, s));
                } else {
                    osrLocal = graph.addOrUnique(new OSRLocalNode(i, s));
                }
                proxy.replaceAndDelete(osrLocal);
            } else {
                assert value == null || value instanceof OSRLocalNode;
            }
        }

        osr.replaceAtUsages(InputType.Guard, osrStart);
        Debug.dump(Debug.DETAILED_LEVEL, graph, "OnStackReplacement after replacing entry proxies");
        GraphUtil.killCFG(start);
        Debug.dump(Debug.DETAILED_LEVEL, graph, "OnStackReplacement result");
        new DeadCodeEliminationPhase(Required).apply(graph);

        if (currentOSRWithLocks) {
            OsrWithLocksCount.increment();
            for (int i = osrState.monitorIdCount() - 1; i >= 0; --i) {
                MonitorIdNode id = osrState.monitorIdAt(i);
                ValueNode lockedObject = osrState.lockAt(i);
                OSRMonitorEnterNode osrMonitorEnter = graph.add(new OSRMonitorEnterNode(lockedObject, id));
                for (Node usage : id.usages()) {
                    if (usage instanceof AccessMonitorNode) {
                        AccessMonitorNode access = (AccessMonitorNode) usage;
                        access.setObject(lockedObject);
                    }
                }
                FixedNode oldNext = osrStart.next();
                oldNext.replaceAtPredecessor(null);
                osrMonitorEnter.setNext(oldNext);
                osrStart.setNext(osrMonitorEnter);
            }
            Debug.dump(Debug.DETAILED_LEVEL, graph, "After inserting OSR monitor enters");
            /*
             * Ensure balanced monitorenter - monitorexit
             *
             * Ensure that there is no monitor exit without a monitor enter in the graph. If there
             * is one this can only be done by bytecode as we have the monitor enter before the OSR
             * loop but the exit in a path of the loop that must be under a condition, else it will
             * throw an IllegalStateException anyway in the 2.iteration
             */
            for (MonitorExitNode exit : graph.getNodes(MonitorExitNode.TYPE)) {
                MonitorIdNode id = exit.getMonitorId();
                if (id.usages().filter(MonitorEnterNode.class).count() != 1) {
                    throw new PermanentBailoutException("Unbalanced monitor enter-exit in OSR compilation with locks. Object is locked before the loop but released inside the loop.");
                }
            }
        }
        Debug.dump(Debug.DETAILED_LEVEL, graph, "OnStackReplacement result");
        new DeadCodeEliminationPhase(Required).apply(graph);
        /*
         * There must not be any parameter nodes left after OSR compilation.
         */
        assert graph.getNodes(ParameterNode.TYPE).count() == 0 : "OSR Compilation contains references to parameters.";
    }

    private static EntryMarkerNode getEntryMarker(StructuredGraph graph) {
        NodeIterable<EntryMarkerNode> osrNodes = graph.getNodes(EntryMarkerNode.TYPE);
        EntryMarkerNode osr = osrNodes.first();
        if (osr == null) {
            throw new PermanentBailoutException("No OnStackReplacementNode generated");
        }
        if (osrNodes.count() > 1) {
            throw new GraalError("Multiple OnStackReplacementNodes generated");
        }
        if (osr.stateAfter().stackSize() != 0) {
            throw new PermanentBailoutException("OSR with stack entries not supported: %s", osr.stateAfter().toString(Verbosity.Debugger));
        }
        return osr;
    }

    private static LoopBeginNode osrLoop(EntryMarkerNode osr) {
        // Check that there is an OSR loop for the OSR begin
        LoopsData loops = new LoopsData(osr.graph());
        Loop<Block> l = loops.getCFG().getNodeToBlock().get(osr).getLoop();
        if (l == null) {
            return null;
        }
        return (LoopBeginNode) l.getHeader().getBeginNode();
    }

    private static boolean osrWithLocks(EntryMarkerNode osr) {
        return osr.stateAfter().locksSize() != 0;
    }

    @Override
    public float codeSizeIncrease() {
        return 5.0f;
    }
}

/*
 * [The "BSD licence"]
 * Copyright (c) 2010 Ben Gruver (JesusFreke)
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.jf.baksmali.Adaptors;

import org.jf.baksmali.Adaptors.Format.*;
import org.jf.baksmali.IndentingPrintWriter;
import org.jf.baksmali.Renderers.IntegerRenderer;
import org.jf.baksmali.baksmali;
import org.jf.dexlib.*;
import org.jf.dexlib.Code.*;
import org.jf.dexlib.Code.Analysis.AnalyzedInstruction;
import org.jf.dexlib.Code.Analysis.MethodAnalyzer;
import org.jf.dexlib.Code.Analysis.ValidationException;
import org.jf.dexlib.Code.Format.Format;
import org.jf.dexlib.Debug.DebugInstructionIterator;
import org.jf.dexlib.Util.AccessFlags;
import org.jf.dexlib.Util.ExceptionWithContext;
import org.jf.dexlib.Util.SparseIntArray;

import java.io.IOException;
import java.util.*;
                                                                          git
public class MethodDefinition {
    private final ClassDataItem.EncodedMethod encodedMethod;
    private final MethodAnalyzer methodAnalyzer;

    private final LabelCache labelCache = new LabelCache();

    private final SparseIntArray packedSwitchMap;
    private final SparseIntArray sparseSwitchMap;
    private final SparseIntArray instructionMap;

    public MethodDefinition(ClassDataItem.EncodedMethod encodedMethod) {


        try {
            this.encodedMethod = encodedMethod;

            //TODO: what about try/catch blocks inside the dead code? those will need to be commented out too. ugh.

            if (encodedMethod.codeItem != null) {
                methodAnalyzer = new MethodAnalyzer(encodedMethod, baksmali.deodex);
                List<AnalyzedInstruction> instructions = methodAnalyzer.getInstructions();

                packedSwitchMap = new SparseIntArray(1);
                sparseSwitchMap = new SparseIntArray(1);
                instructionMap = new SparseIntArray(instructions.size());

                int currentCodeAddress = 0;
                for (int i=0; i<instructions.size(); i++) {
                    AnalyzedInstruction instruction = instructions.get(i);
                    if (instruction.getInstruction().opcode == Opcode.PACKED_SWITCH) {
                        packedSwitchMap.append(
                                currentCodeAddress + ((OffsetInstruction)instruction.getInstruction()).getTargetAddressOffset(),
                                currentCodeAddress);
                    } else if (instruction.getInstruction().opcode == Opcode.SPARSE_SWITCH) {
                        sparseSwitchMap.append(
                                currentCodeAddress + ((OffsetInstruction)instruction.getInstruction()).getTargetAddressOffset(),
                                currentCodeAddress);
                    }
                    instructionMap.append(currentCodeAddress, i);
                    currentCodeAddress += instruction.getInstruction().getSize(currentCodeAddress);
                }
            } else {
                packedSwitchMap = null;
                sparseSwitchMap = null;
                instructionMap = null;
                methodAnalyzer = null;
            }
        }catch (Exception ex) {
            throw ExceptionWithContext.withContext(ex, String.format("Error while processing method %s",
                    encodedMethod.method.getMethodString()));
        }
    }

    public void writeTo(IndentingPrintWriter writer, AnnotationSetItem annotationSet,
                        AnnotationSetRefList parameterAnnotations) throws IOException {
        final CodeItem codeItem = encodedMethod.codeItem;

        writer.write(".method ");
        writeAccessFlags(writer, encodedMethod);
        writer.write(encodedMethod.method.getMethodName().getStringValue());
        writer.println(encodedMethod.method.getPrototype().getPrototypeString());

        writer.indent(4);
        if (codeItem != null) {
            if (baksmali.useLocalsDirective) {
                writer.write(".locals ");
            } else {
                writer.write(".registers ");
            }
            writer.println(getRegisterCount(encodedMethod));
            writeParameters(writer, codeItem, parameterAnnotations);
            if (annotationSet != null) {
                AnnotationFormatter.writeTo(writer, annotationSet);
            }

            writer.println();

            for (MethodItem methodItem: getMethodItems()) {
                if (methodItem.writeTo(writer)) {
                    writer.write('\n');
                }
            }
        } else {
            if (annotationSet != null) {
                AnnotationFormatter.writeTo(writer, annotationSet);
            }
        }
        writer.deindent(4);
        writer.println(".end method");
    }

    private static int getRegisterCount(ClassDataItem.EncodedMethod encodedMethod)
    {
        int totalRegisters = encodedMethod.codeItem.getRegisterCount();
        if (baksmali.useLocalsDirective) {
            int parameterRegisters = encodedMethod.method.getPrototype().getParameterRegisterCount();
            if ((encodedMethod.accessFlags & AccessFlags.STATIC.getValue()) == 0) {
                parameterRegisters++;
            }
            return totalRegisters - parameterRegisters;
        }
        return totalRegisters;
    }

    private static void writeAccessFlags(IndentingPrintWriter writer, ClassDataItem.EncodedMethod encodedMethod) {
        for (AccessFlags accessFlag: AccessFlags.getAccessFlagsForMethod(encodedMethod.accessFlags)) {
            writer.write(accessFlag.toString());
            writer.write(' ');
        }
    }

    private static void writeParameters(IndentingPrintWriter writer, CodeItem codeItem,
                                        AnnotationSetRefList parameterAnnotations) throws IOException {
        DebugInfoItem debugInfoItem = null;
        if (baksmali.outputDebugInfo && codeItem != null) {
            debugInfoItem = codeItem.getDebugInfo();
        }

        int parameterCount = 0;
        AnnotationSetItem[] annotations;
        StringIdItem[] parameterNames = null;

        if (parameterAnnotations != null) {
            annotations = parameterAnnotations.getAnnotationSets();
            parameterCount = annotations.length;
        } else {
            annotations = new AnnotationSetItem[0];
        }

        if (debugInfoItem != null) {
            parameterNames = debugInfoItem.getParameterNames();
        }
        if (parameterNames == null) {
            parameterNames = new StringIdItem[0];
        }

        if (parameterCount < parameterNames.length) {
            parameterCount = parameterNames.length;
        }

        for (int i=0; i<parameterCount; i++) {
            AnnotationSetItem annotationSet = null;
            if (i < annotations.length) {
                annotationSet = annotations[i];
            }

            StringIdItem parameterName = null;
            if (i < parameterNames.length) {
                parameterName = parameterNames[i];
            }

            writer.write(".parameter");

            if (parameterName != null) {
                writer.write('"');
                writer.write(parameterName.getStringValue());
                writer.write('"');
            }

            writer.println();
            if (annotationSet != null) {
                writer.indent(4);
                AnnotationFormatter.writeTo(writer, annotationSet);
                writer.deindent(4);

                writer.println(".end parameter");
            }
        }
    }

    public LabelCache getLabelCache() {
        return labelCache;
    }

    public ValidationException getValidationException() {
        if (methodAnalyzer == null) {
            return null;
        }

        return methodAnalyzer.getValidationException();
    }

    public int getPackedSwitchBaseAddress(int packedSwitchDataAddress) {
        int packedSwitchBaseAddress = this.packedSwitchMap.get(packedSwitchDataAddress, -1);

        if (packedSwitchBaseAddress == -1) {
            throw new RuntimeException("Could not find the packed switch statement corresponding to the packed " +
                    "switch data at address " + packedSwitchDataAddress);
        }

        return packedSwitchBaseAddress;
    }

    public int getSparseSwitchBaseAddress(int sparseSwitchDataAddress) {
        int sparseSwitchBaseAddress = this.sparseSwitchMap.get(sparseSwitchDataAddress, -1);

        if (sparseSwitchBaseAddress == -1) {
            throw new RuntimeException("Could not find the sparse switch statement corresponding to the sparse " +
                    "switch data at address " + sparseSwitchDataAddress);
        }

        return sparseSwitchBaseAddress;
    }

    /**
     * @param instructions The instructions array for this method
     * @param instruction The instruction
     * @return true if the specified instruction is a NOP, and the next instruction is one of the variable sized
     * switch/array data structures
     */
    private boolean isInstructionPaddingNop(List<AnalyzedInstruction> instructions, AnalyzedInstruction instruction) {
        if (instruction.getInstruction().opcode != Opcode.NOP ||
            instruction.getInstruction().getFormat().variableSizeFormat) {

            return false;
        }

        if (instruction.getInstructionIndex() == instructions.size()-1) {
            return false;
        }

        AnalyzedInstruction nextInstruction = instructions.get(instruction.getInstructionIndex()+1);
        if (nextInstruction.getInstruction().getFormat().variableSizeFormat) {
            return true;
        }
        return false;
    }

    private List<MethodItem> getMethodItems() {
        List<MethodItem> methodItems = new ArrayList<MethodItem>();

        if (encodedMethod.codeItem == null) {
            return methodItems;
        }

        if (baksmali.registerInfo != 0 || baksmali.deodex || baksmali.verify) {
            methodAnalyzer.analyze();

            ValidationException validationException = methodAnalyzer.getValidationException();
            if (validationException != null) {
                methodItems.add(new CommentMethodItem(
                        String.format("ValidationException: %s" ,validationException.getMessage()),
                        validationException.getCodeAddress(), Integer.MIN_VALUE));
            } else if (baksmali.verify) {
                methodAnalyzer.verify();

                validationException = methodAnalyzer.getValidationException();
                if (validationException != null) {
                    methodItems.add(new CommentMethodItem(
                            String.format("ValidationException: %s" ,validationException.getMessage()),
                            validationException.getCodeAddress(), Integer.MIN_VALUE));
                }
            }
        }
        List<AnalyzedInstruction> instructions = methodAnalyzer.getInstructions();

        AnalyzedInstruction lastInstruction = null;

        for (int i=instructions.size()-1; i>=0; i--) {
            AnalyzedInstruction instruction = instructions.get(i);

            if (!instruction.isDead()) {
                lastInstruction = instruction;
                break;
            }
        }

        boolean lastIsUnreachable = false;

        int currentCodeAddress = 0;
        for (int i=0; i<instructions.size(); i++) {
            AnalyzedInstruction instruction = instructions.get(i);

            MethodItem methodItem = InstructionMethodItemFactory.makeInstructionFormatMethodItem(this,
                    encodedMethod.codeItem, currentCodeAddress, instruction.isDead(), instruction.getInstruction(),
                    instruction == lastInstruction);

            if (instruction.isDead() && !instruction.getInstruction().getFormat().variableSizeFormat) {
                methodItems.add(new CommentedOutMethodItem(methodItem));
                lastIsUnreachable = false;
            } else if ( instruction.getPredecessorCount() == 0 &&
                        !instruction.getInstruction().getFormat().variableSizeFormat &&
                        !isInstructionPaddingNop(instructions, instruction)) {

                if (!lastIsUnreachable) {
                    methodItems.add(
                        new CommentMethodItem("Unreachable code", currentCodeAddress, Double.MIN_VALUE));
                }

                methodItems.add(new CommentedOutMethodItem(methodItem));
                    lastIsUnreachable = true;
            } else {
                methodItems.add(methodItem);
                lastIsUnreachable = false;
            }

            if (instruction.getInstruction().getFormat() == Format.UnresolvedNullReference) {
                methodItems.add(new CommentedOutMethodItem(
                        InstructionMethodItemFactory.makeInstructionFormatMethodItem(this, encodedMethod.codeItem,
                                currentCodeAddress, instruction.isDead(), instruction.getOriginalInstruction(),
                                false)));
            }

            if (i != instructions.size() - 1) {
                methodItems.add(new BlankMethodItem(currentCodeAddress));
            }

            if (baksmali.addCodeOffsets) {
                methodItems.add(new MethodItem(currentCodeAddress) {

                    @Override
                    public double getSortOrder() {
                        return -1000;
                    }

                    @Override
                    public boolean writeTo(IndentingPrintWriter writer) throws IOException {
                        writer.write("#@");
                        IntegerRenderer.writeUnsignedTo(writer, codeAddress);
                        return true;
                    }
                });
            }

            if (baksmali.registerInfo != 0 && !instruction.getInstruction().getFormat().variableSizeFormat) {
                methodItems.add(
                        new PreInstructionRegisterInfoMethodItem(instruction, methodAnalyzer, currentCodeAddress));

                methodItems.add(
                        new PostInstructionRegisterInfoMethodItem(instruction, methodAnalyzer, currentCodeAddress));
            }

            currentCodeAddress += instruction.getInstruction().getSize(currentCodeAddress);
        }

        addTries(methodItems);
        addDebugInfo(methodItems);

        if (baksmali.useSequentialLabels) {
            setLabelSequentialNumbers();
        }


        for (LabelMethodItem labelMethodItem: labelCache.getLabels()) {
            if (labelMethodItem.isCommentedOut()) {
                methodItems.add(new CommentedOutMethodItem(labelMethodItem));
            } else {
                methodItems.add(labelMethodItem);
            }
        }

        Collections.sort(methodItems);

        return methodItems;
    }

    private void addTries(List<MethodItem> methodItems) {
        if (encodedMethod.codeItem == null || encodedMethod.codeItem.getTries() == null) {
            return;
        }

        Instruction[] instructions = encodedMethod.codeItem.getInstructions();

        for (CodeItem.TryItem tryItem: encodedMethod.codeItem.getTries()) {
            int startAddress = tryItem.getStartCodeAddress();
            int endAddress = tryItem.getStartCodeAddress() + tryItem.getTryLength();

            /**
             * The end address points to the address immediately after the end of the last
             * instruction that the try block covers. We want the .catch directive and end_try
             * label to be associated with the last covered instruction, so we need to get
             * the address for that instruction
             */

            int index = instructionMap.get(endAddress, -1);
            int lastInstructionAddress;

            /**
             * If we couldn't find the index, then the try block probably extends to the last instruction in the
             * method, and so endAddress would be the address immediately after the end of the last instruction.
             * Check to make sure this is the case, if not, throw an exception.
             */
            if (index == -1) {
                Instruction lastInstruction = instructions[instructions.length - 1];
                lastInstructionAddress = instructionMap.keyAt(instructionMap.size() - 1);

                if (endAddress != lastInstructionAddress + lastInstruction.getSize(lastInstructionAddress)) {
                    throw new RuntimeException("Invalid code offset " + endAddress + " for the try block end address");
                }
            } else {
                if (index == 0) {
                    throw new RuntimeException("Unexpected instruction index");
                }
                Instruction lastInstruction = instructions[index - 1];

                if (lastInstruction.getFormat().variableSizeFormat) {
                    throw new RuntimeException("This try block unexpectedly ends on a switch/array data block.");
                }

                //getSize for non-variable size formats should return the same size regardless of code address, so just
                //use a dummy address of "0"
                lastInstructionAddress = endAddress - lastInstruction.getSize(0);
            }

            //add the catch all handler if it exists
            int catchAllAddress = tryItem.encodedCatchHandler.getCatchAllHandlerAddress();
            if (catchAllAddress != -1) {
                CatchMethodItem catchAllMethodItem = new CatchMethodItem(labelCache, lastInstructionAddress, null,
                        startAddress, endAddress, catchAllAddress);
                methodItems.add(catchAllMethodItem);
            }

            //add the rest of the handlers
            for (CodeItem.EncodedTypeAddrPair handler: tryItem.encodedCatchHandler.handlers) {
                //use the address from the last covered instruction
                CatchMethodItem catchMethodItem = new CatchMethodItem(labelCache, lastInstructionAddress,
                        handler.exceptionType, startAddress, endAddress, handler.getHandlerAddress());
                methodItems.add(catchMethodItem);
            }
        }
    }

    private void addDebugInfo(final List<MethodItem> methodItems) {
        if (encodedMethod.codeItem == null || encodedMethod.codeItem.getDebugInfo() == null) {
            return;
        }

        final CodeItem codeItem = encodedMethod.codeItem;
        DebugInfoItem debugInfoItem = codeItem.getDebugInfo();

        DebugInstructionIterator.DecodeInstructions(debugInfoItem, codeItem.getRegisterCount(),
                new DebugInstructionIterator.ProcessDecodedDebugInstructionDelegate() {
                    @Override
                    public void ProcessStartLocal(final int codeAddress, final int length, final int registerNum,
                                                  final StringIdItem name, final TypeIdItem type) {
                        methodItems.add(new DebugMethodItem(codeAddress, -1) {
                            @Override
                            public boolean writeTo(IndentingPrintWriter writer) throws IOException {
                                writeStartLocal(writer, codeItem, registerNum, name, type, null);
                                return true;
                            }
                        });
                    }

                    @Override
                    public void ProcessStartLocalExtended(final int codeAddress, final int length,
                                                          final int registerNum, final StringIdItem name,
                                                          final TypeIdItem type, final StringIdItem signature) {
                        methodItems.add(new DebugMethodItem(codeAddress, -1) {
                            @Override
                            public boolean writeTo(IndentingPrintWriter writer) throws IOException {
                                writeStartLocal(writer, codeItem, registerNum, name, type, signature);
                                return true;
                            }
                        });
                    }

                    @Override
                    public void ProcessEndLocal(final int codeAddress, final int length, final int registerNum,
                                                final StringIdItem name, final TypeIdItem type,
                                                final StringIdItem signature) {
                        methodItems.add(new DebugMethodItem(codeAddress, -1) {
                            @Override
                            public boolean writeTo(IndentingPrintWriter writer) throws IOException {
                                writeEndLocal(writer, codeItem, registerNum, name, type, signature);
                                return true;
                            }
                        });
                    }

                    @Override
                    public void ProcessRestartLocal(final int codeAddress, final int length, final int registerNum,
                                                final StringIdItem name, final TypeIdItem type,
                                                final StringIdItem signature) {
                        methodItems.add(new DebugMethodItem(codeAddress, -1) {
                            @Override
                            public boolean writeTo(IndentingPrintWriter writer) throws IOException {
                                writeRestartLocal(writer, codeItem, registerNum, name, type, signature);
                                return true;
                            }
                        });
                    }

                    @Override
                    public void ProcessSetPrologueEnd(int codeAddress) {
                        methodItems.add(new DebugMethodItem(codeAddress, -4) {
                            @Override
                            public boolean writeTo(IndentingPrintWriter writer) throws IOException {
                                writeEndPrologue(writer);
                                return true;
                            }
                        });
                    }

                    @Override
                    public void ProcessSetEpilogueBegin(int codeAddress) {
                        methodItems.add(new DebugMethodItem(codeAddress, -4) {
                            @Override
                            public boolean writeTo(IndentingPrintWriter writer) throws IOException {
                                writeBeginEpilogue(writer);
                                return true;
                            }
                        });
                    }

                    @Override
                    public void ProcessSetFile(int codeAddress, int length, final StringIdItem name) {
                        methodItems.add(new DebugMethodItem(codeAddress, -3) {
                            @Override
                            public boolean writeTo(IndentingPrintWriter writer) throws IOException {
                                writeSetFile(writer, name.getStringValue());
                                return true;
                            }
                        });
                    }

                    @Override
                    public void ProcessLineEmit(int codeAddress, final int line) {
                        methodItems.add(new DebugMethodItem(codeAddress, -2) {
                            @Override
                            public boolean writeTo(IndentingPrintWriter writer) throws IOException {
                                writeLine(writer, line);
                                return true;
                            }
                         });
                    }
                });
    }

    private void setLabelSequentialNumbers() {
        HashMap<String, Integer> nextLabelSequenceByType = new HashMap<String, Integer>();
        ArrayList<LabelMethodItem> sortedLabels = new ArrayList<LabelMethodItem>(labelCache.getLabels());

        //sort the labels by their location in the method
        Collections.sort(sortedLabels);

        for (LabelMethodItem labelMethodItem: sortedLabels) {
            Integer labelSequence = nextLabelSequenceByType.get(labelMethodItem.getLabelPrefix());
            if (labelSequence == null) {
                labelSequence = 0;
            }
            labelMethodItem.setLabelSequence(labelSequence);
            nextLabelSequenceByType.put(labelMethodItem.getLabelPrefix(), labelSequence + 1);
        }
    }

    public static class LabelCache {
        protected HashMap<LabelMethodItem, LabelMethodItem> labels = new HashMap<LabelMethodItem, LabelMethodItem>();

        public LabelCache() {
        }

        public LabelMethodItem internLabel(LabelMethodItem labelMethodItem) {
            LabelMethodItem internedLabelMethodItem = labels.get(labelMethodItem);
            if (internedLabelMethodItem != null) {
                if (!labelMethodItem.isCommentedOut()) {
                    internedLabelMethodItem.setUncommented();
                }
                return internedLabelMethodItem;
            }
            labels.put(labelMethodItem, labelMethodItem);
            return labelMethodItem;
        }


        public Collection<LabelMethodItem> getLabels() {
            return labels.values();
        }
    }
}

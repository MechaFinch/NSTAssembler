package notsotiny.nstasm.assembly;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import asmlib.util.FileLocator;
import fr.cenotelie.hime.redist.ASTNode;
import fr.cenotelie.hime.redist.Symbol;
import notsotiny.lib.data.Triple;
import notsotiny.lib.util.ASTLogger;
import notsotiny.lib.util.ASTUtil;
import notsotiny.nstasm.AssemblyException;
import notsotiny.nstasm.asmparts.ASMArgument;
import notsotiny.nstasm.asmparts.ASMComponent;
import notsotiny.nstasm.asmparts.ASMConstant;
import notsotiny.nstasm.asmparts.ASMExpression;
import notsotiny.nstasm.asmparts.ASMInstruction;
import notsotiny.nstasm.asmparts.ASMInstruction.ASMPacking;
import notsotiny.nstasm.asmparts.ASMLabel;
import notsotiny.nstasm.asmparts.ASMLocation;
import notsotiny.nstasm.asmparts.ASMMemory;
import notsotiny.nstasm.asmparts.ASMObject;
import notsotiny.nstasm.asmparts.ASMReference;
import notsotiny.nstasm.asmparts.ASMReference.ReferenceType;
import notsotiny.nstasm.asmparts.ASMRegister;
import notsotiny.nstasm.asmparts.ASMRepetition;
import notsotiny.nstasm.asmparts.ASMUninitializedData;
import notsotiny.nstasm.asmparts.ASMValue;
import notsotiny.nstasm.asmparts.ASMExpression.ASMOperation;
import notsotiny.nstasm.asmparts.ASMInitializedData;
import notsotiny.nstasm.parser.NstassemblerLexer;
import notsotiny.nstasm.parser.NstassemblerParser;
import notsotiny.sim.ops.Opcode;

/**
 * Parses abstract syntax trees into ASMObjects 
 */
public class ASMParser {
    
    private static Logger LOG = Logger.getLogger(ASMParser.class.getName());
    private static ASTLogger ALOG = new ASTLogger(LOG);
    
    /**
     * Parse an AST with the given name and file locator
     * @param astRoot
     * @param defaultLibraryName
     * @param locator
     * @return
     * @throws AssemblyException 
     */
    public static ASMObject parseASM(ASTNode astRoot, String defaultLibraryName, FileLocator locator) throws AssemblyException {
        LOG.fine("Parsing assembly file " + defaultLibraryName);
        
        // ASMObject
        ASMObject asmObj = new ASMObject(defaultLibraryName);
        
        // Context/scoped things
        ASMContext context = new ASMContext();
        
        // Parse
        for(ASTNode node : astRoot.getChildren()) {
            parseTopLevel(node, context, asmObj, locator);
        }
        
        if(LOG.isLoggable(Level.FINEST)) {
            LOG.finest("- Result -");
            LOG.finest(asmObj.getName());
            LOG.finest(asmObj.allowsLengthOptimization() ? "optimization allowed" : "optimization disallowed");
            LOG.finest(asmObj.isPrivileged() ? "privileged" : "unprivileged");
            LOG.finest("origin " + asmObj.getOrigin());
            
            for(ASMComponent c : asmObj.getComponents()) {
                if(c instanceof ASMLabel) {
                    LOG.finest(c + ":");
                } else {
                    LOG.finest("\t" + c);
                }
            }
        }
        
        return asmObj;
    }
    
    /**
     * Parse nodes that appear at the top level
     * Macro definitions, labels, instructions, directives, initialized data, uninitialized data
     * @param node
     * @param context
     * @param asmObj
     * @param locator
     * @throws AssemblyException 
     */
    private static void parseTopLevel(ASTNode node, ASMContext context, ASMObject asmObj, FileLocator locator) throws AssemblyException {
        // what do
        switch(node.getSymbol().getID()) {
            case NstassemblerParser.ID.VARIABLE_LINE:
                // 'line'
                parseLine(node, null, context, asmObj, locator);
                break;
                
            case NstassemblerParser.ID.VARIABLE_DEF_MACRO:
                // %define macro
                parseDefinitionMacro(node, context);
                break;
                
            case NstassemblerParser.ID.VARIABLE_SINGLE_MACRO:
                // Single-line macro
                parseSingleLineMacro(node, context);
                break;
                
            case NstassemblerParser.ID.VARIABLE_MULTI_MACRO:
                // Multi-line macro
                parseMultiLineMacro(node, context);
                break;
            
            default:
                ALOG.severe(node, "Unexpected node at top level: " + ASTUtil.detailed(node));
                throw new AssemblyException();
        }
    }
    
    /**
     * Parse a LINE which is an actual line
     * @param node
     * @param invokNode
     * @param context
     * @param asmObj
     * @param locator
     * @throws AssemblyException
     */
    private static void parseLine(ASTNode node, ASTNode invokNode, ASMContext context, ASMObject asmObj, FileLocator locator) throws AssemblyException {
        List<ASTNode> children = node.getChildren();
        
        // Handle empty lines
        if(children.size() == 0) {
            return;
        }
        
        if(LOG.isLoggable(Level.FINEST)) { 
            LOG.finest("Parsing " + ASTUtil.detailed(node, 1));
        }
        
        // Do we have a label?
        boolean hasLabel = children.get(0).getSymbol().getID() == NstassemblerParser.ID.VARIABLE_LABEL;
        ASTNode contents;
        
        // Process label if present
        if(hasLabel) {
            // Has label
            ASTNode labelNameNode = children.get(0).getChildren().get(0);
            Triple<ASTNode, Integer, Integer> unTrip = unmacro(labelNameNode, invokNode, context, true); 
            ASTNode labelID = unTrip.a;
            leave(unTrip, context);
            
            // Make sure it didn't get macro'd into something invalid
            switch(labelID.getSymbol().getID()) {
                case NstassemblerLexer.ID.TERMINAL_NAME,
                     NstassemblerLexer.ID.TERMINAL_LOCAL,
                     NstassemblerLexer.ID.TERMINAL_MACROLOCAL:
                    break;
                
                default:
                    logWithInvok(Level.SEVERE, labelID, labelNameNode, "Invalid label name: " + ASTUtil.detailed(labelID));
            }
            
            // What are we dealing with
            String unqualifiedName = labelID.getValue();
            boolean isMacroLabel = unqualifiedName.startsWith("%%");
            boolean isLocal = unqualifiedName.startsWith(".");
            boolean isTopLevel = !(isLocal || isMacroLabel);
            
            String name;
            if(isTopLevel) {
                context.setTopLevelLabel(unqualifiedName);
                name = unqualifiedName;
            } else {
                name = context.getEnclosingName(isMacroLabel) + unqualifiedName;
            }
            
            if(LOG.isLoggable(Level.FINER)) {
                LOG.finer("Created label " + name);
            }
            
            asmObj.addComponent(new ASMLabel(name));
            
            // Do we have anything other than the label?
            if(children.size() == 2) {
                // Yes
                contents = children.get(1);
            } else {
                // No, done
                return;
            }
        } else {
            // No label
            contents = children.get(0);
        }
        
        // Process contents
        parseLineContents(contents, invokNode, context, asmObj, locator);
    }
    
    /**
     * Parse the contents of a LINE which is an actual line
     * @param contents
     * @param invokNode
     * @param context
     * @param asmObj
     * @param locator
     * @throws AssemblyException
     */
    private static void parseLineContents(ASTNode contents, ASTNode invokNode, ASMContext context, ASMObject asmObj, FileLocator locator) throws AssemblyException {
        switch(contents.getSymbol().getID()) {
            case NstassemblerParser.ID.VARIABLE_INSTRUCTION:
                // Instruction
                parseInstruction(contents, invokNode, context, asmObj);
                break;
            
            case NstassemblerParser.ID.VARIABLE_DIRECTIVE:
                // Directive
                parseDirective(contents, invokNode, context, asmObj, locator);
                break;
                
            case NstassemblerParser.ID.VARIABLE_INITIALIZED:
                // Initialized data
                parseInitializedData(contents, invokNode, context, asmObj);
                break;
            
            case NstassemblerParser.ID.VARIABLE_UNINITIALIZED:
                // Uninitialized data
                parseUninitializedData(contents, invokNode, context, asmObj);
                break;
            
            case NstassemblerParser.ID.VARIABLE_INVOCATION:
                // Macro, could be multiline
                Triple<ASTNode, Integer, Integer> unTrip = unmacro(contents, invokNode, context, false); 
                ASTNode realContents = unTrip.a;
                
                // is it multiline
                if(realContents.getSymbol().getID() == Symbol.SID_EPSILON) {
                    // Multiline
                    for(ASTNode contentNode : realContents.getChildren()) {
                        parseLine(contentNode, contents, context, asmObj, locator);
                    }
                } else {
                    // single line
                    parseLine(realContents, contents, context, asmObj, locator);
                }
                
                leave(unTrip, context);
                break;
                
            case NstassemblerLexer.ID.TERMINAL_NAME,
                 NstassemblerLexer.ID.TERMINAL_LOCAL,
                 NstassemblerLexer.ID.TERMINAL_MACROLOCAL:
                // probably a macro
                unTrip = unmacro(contents, invokNode, context, false);
                realContents = unTrip.a;
                
                if(realContents != contents) {
                    // Was a macro
                    parseLine(realContents, contents, context, asmObj, locator);
                    leave(unTrip, context);
                    break;
                }
                
                // fallthrough otherwise
            
            default:
                // Oh no
                logWithInvok(Level.SEVERE, contents, invokNode, "Unexpected node as line: " + ASTUtil.detailed(contents, 1));
                throw new AssemblyException();
        }
    }
    
    /**
     * Parse uninitialized data
     * @param uninitNode
     * @param invokNode
     * @param context
     * @param asmObj
     * @throws AssemblyException
     */
    private static void parseUninitializedData(ASTNode uninitNode, ASTNode invokNode, ASMContext context, ASMObject asmObj) throws AssemblyException {
        if(LOG.isLoggable(Level.FINEST)) {
            LOG.finest("Parsing " + ASTUtil.detailed(uninitNode));
        }
        
        List<ASTNode> children = uninitNode.getChildren();
        
        // Get data size
        int dataSize = switch(children.get(0).getSymbol().getID()) {
            case NstassemblerLexer.ID.TERMINAL_KW_RESB  -> 1;
            case NstassemblerLexer.ID.TERMINAL_KW_RESW  -> 2;
            case NstassemblerLexer.ID.TERMINAL_KW_RESP  -> 4;
            default -> throw new IllegalStateException("Unreachable");
        };
        
        // Get reserved amount
        ASMValue reserveAmount = parseExpression(children.get(1), invokNode, context);
        
        // Multiply by data size
        ASMValue reservedSize = ASMExpression.valueOf(ASMOperation.MUL, new ASMConstant(dataSize), reserveAmount);
        
        // Report & add
        ASMUninitializedData udata = new ASMUninitializedData(reservedSize);
        
        if(LOG.isLoggable(Level.FINER)) {
            LOG.finer("Got " + udata);
        }
        
        asmObj.addComponent(udata);
    }
    
    /**
     * Parse initialized data
     * @param initNode
     * @param invokNode
     * @param context
     * @param asmObj
     * @throws AssemblyException
     */
    private static void parseInitializedData(ASTNode initNode, ASTNode invokNode, ASMContext context, ASMObject asmObj) throws AssemblyException {
        if(LOG.isLoggable(Level.FINEST)) {
            LOG.finest("Parsing " + ASTUtil.detailed(initNode));
        }
        
        List<ASTNode> children = initNode.getChildren();
        
        // Get data size
        int dataSize = switch(children.get(0).getSymbol().getID()) {
            case NstassemblerLexer.ID.TERMINAL_KW_DB    -> 1;
            case NstassemblerLexer.ID.TERMINAL_KW_DW    -> 2;
            case NstassemblerLexer.ID.TERMINAL_KW_DP    -> 4;
            default -> throw new IllegalStateException("Unreachable");
        };
        
        // Evaluate elements
        List<ASTNode> vNodes = children.get(1).getChildren();
        List<ASMValue> values = new ArrayList<>(vNodes.size());
        
        for(ASTNode vNode : vNodes) {
            // Strings are allowed here
            if(vNode.getSymbol().getID() == NstassemblerLexer.ID.TERMINAL_STRING) {
                // Add characters
                String str = vNode.getValue();
                str = str.substring(1, str.length() - 1);
                
                for(char c : str.toCharArray()) {
                    values.add(new ASMConstant(c));
                }
            } else {
                // Not a string
                values.add(parseExpression(vNode, invokNode, context));
            }
        }
        
        // Report & add
        ASMInitializedData id = new ASMInitializedData(values, dataSize);
        
        if(LOG.isLoggable(Level.FINER)) {
            LOG.finer("Got " + id);
        }
        
        asmObj.addComponent(id);
    }
    
    /**
     * Parse a directive
     * @param dirNode
     * @param invokNode
     * @param context
     * @param asmObj
     * @throws AssemblyException
     */
    private static void parseDirective(ASTNode dirNode, ASTNode invokNode, ASMContext context, ASMObject asmObj, FileLocator locator) throws AssemblyException {
        if(LOG.isLoggable(Level.FINEST)) {
            LOG.finest("Parsing " + ASTUtil.detailed(dirNode));
        }
        
        List<ASTNode> children = dirNode.getChildren();
        
        // what do
        int dirID = children.get(0).getSymbol().getID();
        switch(dirID) {
            case NstassemblerLexer.ID.TERMINAL_KW_REPEAT:
                // Repetition
                // repeat <times>, line
                ASMValue repetitions = parseExpression(children.get(1), invokNode, context);
                
                // Parse line to temporary, then clone contents <repetitions> times
                ASMObject tmpObj = new ASMObject("tmp");
                parseLineContents(children.get(2), invokNode, context, tmpObj, locator);
                
                ASMRepetition rep = new ASMRepetition(tmpObj.getComponents(), repetitions);
                
                if(LOG.isLoggable(Level.FINER)) {
                    LOG.finer("Got " + rep);
                }
                
                asmObj.addComponent(rep);
                break;
                
            case NstassemblerLexer.ID.TERMINAL_KW_INCLUDE:
                // Library inclusion
                String fileName = children.get(1).getValue(),
                       localName = children.get(2).getValue();
                fileName = fileName.substring(1, fileName.length() - 1);
                
                Path givenPath = Paths.get(fileName);
                
                // Add file to locator
                if(!locator.addFile(givenPath)) {
                    // Not found
                    logWithInvok(Level.SEVERE, dirNode, invokNode, "File not found: " + fileName);
                    throw new AssemblyException();
                }
                
                Path realPath;
                try {
                    realPath = locator.getSourceFile(givenPath);
                } catch(NoSuchFileException e) {
                    // should be handled by above condition
                    throw new IllegalStateException("Unreachable");
                }
                
                if(LOG.isLoggable(Level.FINER)) {
                    LOG.finer("Added file " + realPath + " as " + localName);
                }
                
                asmObj.addLibraryMapping(realPath, localName);
                break;
                
            case NstassemblerLexer.ID.TERMINAL_KW_LIBNAME:
                // library name
                asmObj.setName(children.get(1).getValue());
                break;
            
            case NstassemblerLexer.ID.TERMINAL_KW_ORG:
                // origin
                asmObj.setOrigin(parseExpression(children.get(1), invokNode, context));
                break;
            
            case NstassemblerLexer.ID.TERMINAL_KW_NLO:
                // No length optimization
                asmObj.setAllowsLengthOptimization(false);
                break;
            
            case NstassemblerLexer.ID.TERMINAL_KW_PRIVILEGED:
                // Privileged
                asmObj.setPrivileged(true);
                break;
            
            default:
                // Oh no
                logWithInvok(Level.SEVERE, dirNode, invokNode, "Unexpected node as directive: " + ASTUtil.detailed(dirNode, 1));
                throw new AssemblyException();
        }
    }
    
    /**
     * Parse an instruction
     * @param instNode
     * @param invokNode
     * @param context
     * @param asmObj
     * @throws AssemblyException 
     */
    private static void parseInstruction(ASTNode instNode, ASTNode invokNode, ASMContext context, ASMObject asmObj) throws AssemblyException {
        if(LOG.isLoggable(Level.FINEST)) {
            LOG.finest("Parsing " + ASTUtil.detailed(instNode, 1));
        }
        
        List<ASTNode> children = instNode.getChildren();
        
        // Opcode
        String opStr = children.get(0).getValue().toUpperCase(),
               opTxt = opStr; // copy for logging as opStr is modified
        
        // Parse packing & conditions
        // Parse packing size
        ASMPacking packType = ASMPacking.NONE;
        
        if(opStr.startsWith("P") && opStr.contains("4")) {
            packType = ASMPacking.FOURS;
            opStr = opStr.substring(0, opStr.indexOf("4")) + opStr.substring(opStr.indexOf("4") + 1);
        } else if(opStr.startsWith("P") && opStr.contains("8")) {
            packType = ASMPacking.EIGHTS;
            opStr = opStr.substring(0, opStr.indexOf("8")) + opStr.substring(opStr.indexOf("8") + 1);
        }
        
        // Parse conditions
        ASMValue ei8Val = null;
        
        if((opStr.startsWith("J") && !opStr.startsWith("JMP")) || opStr.startsWith("CMOV") || opStr.startsWith("PCMOV")) {
            // Has a condition
            String condStr;
            
            // Isolate condition
            if(opStr.startsWith("J")) {
                condStr = opStr.substring(1);
                opStr = "J";
            } else if(opStr.startsWith("CMOVW")) {
                condStr = opStr.substring(5);
                opStr = "CMOVW";
            } else if(opStr.startsWith("CMOV")) {
                condStr = opStr.substring(4);
                opStr = "CMOV";
            } else if(opStr.startsWith("PCMOV")) {
                condStr = opStr.substring(5);
                opStr = "PCMOV";
            } else {
                logWithInvok(Level.SEVERE, children.get(0), invokNode, "Invalid opcode: " + opTxt);
                throw new AssemblyException();
            }
            
            // Isolate condition packing
            String packedCond = "";
            
            if(condStr.contains(".")) {
                packedCond = condStr.substring(condStr.indexOf(".") + 1);
                condStr = condStr.substring(0, condStr.indexOf("."));
            }
            
            // Convert to EI8
            int condCode = switch(condStr) {
                case "C", "B", "NAE"    -> 0x02;
                case "NC", "NB", "AE"   -> 0x03;
                case "S"                -> 0x04;
                case "NS"               -> 0x05;
                case "O"                -> 0x06;
                case "NO"               -> 0x07;
                case "Z", "E"           -> 0x08;
                case "NZ", "NE"         -> 0x09;
                case "A", "NBE"         -> 0x0A;
                case "BE", "NA"         -> 0x0B;
                case "G", "NLE"         -> 0x0C;
                case "GE", "NL"         -> 0x0D;
                case "L", "NGE"         -> 0x0E;
                case "LE", "NG"         -> 0x0F;
                default -> {
                    logWithInvok(Level.SEVERE, children.get(0), invokNode, "Invalid opcode: " + opTxt);
                    throw new AssemblyException();
                }
            };
            
            int condPack = switch(packedCond) {
                case ""     -> 0x00;
                case "A8"   -> 0x40;
                case "E8"   -> 0x50;
                case "A4"   -> 0x60;
                case "E4"   -> 0x70;
                default     -> {
                    logWithInvok(Level.SEVERE, children.get(0), invokNode, "Invalid opcode: " + opTxt);
                    throw new AssemblyException();
                }
            };
            
            ei8Val = new ASMConstant((long)(condCode | condPack));
        }
        
        // opcode string -> opcode object
        Opcode op;
        String opcodeAttempt = opStr + (ei8Val != null ? "CC" : "") + "_RIM" + (packType == ASMPacking.NONE ? "" : "P");
        
        try {
            // Try _rim
            op = Opcode.valueOf(opcodeAttempt);
        } catch(IllegalArgumentException _) {
            // not a _rim opcode
            try {
                // try just op string
                op = Opcode.valueOf(opStr);
            } catch(IllegalArgumentException _) {
                // JMPA/CALLA reach here, try _RIM32
                try {
                    op = Opcode.valueOf(opStr + "_RIM32");
                } catch(IllegalArgumentException _) {
                    // I should really use less exceptions for this but whateverrrr
                    logWithInvok(Level.SEVERE, children.get(0), invokNode, "Non-converted opcode " + opStr + " -> " + opcodeAttempt + " from " + opTxt);
                    throw new AssemblyException();
                }
            }
        }
        
        // Parse arguments if applicable and create instruction
        ASMInstruction inst = null;
        
        ASMArgument firstArgument = null, secondArgument = null; // dummy values needed cause compiler cant see second if condition implies first if condition 
        
        // First argument
        if(op.dgroup.hasSource || op.dgroup.hasDestination) {
            // do we have an arg?
            if(children.size() < 2) {
                // no :(
                logWithInvok(Level.SEVERE, instNode, invokNode, "Missing first argument for " + opTxt);
                throw new AssemblyException();
            }
            
            // Parse it
            firstArgument = parseArgument(children.get(1), invokNode, context);
            
            // Are we done?
            if(!(op.dgroup.hasSource && op.dgroup.hasDestination)) {
                // No second argument, yes
                // Infer library jump/call as absolute
                // Infer relative jump references as relative
                switch(op) {
                    case CALL_I8, CALL_I16, CALL_I32, CALL_RIM, JMP_I8, JMP_I16, JMP_I32, JMP_RIM, JCC_I8, JCC_RIM,
                         JC_I8, JC_RIM, JNC_I8, JNC_RIM, JS_I8, JS_RIM, JNS_I8, JNS_RIM,
                         JO_I8, JO_RIM, JNO_I8, JNO_RIM, JZ_I8, JZ_RIM, JNZ_I8, JNZ_RIM,
                         JA_I8, JA_RIM, JBE_I8, JBE_RIM, JG_I8, JG_RIM, JGE_I8, JGE_RIM, JL_I8, JL_RIM, JLE_I8, JLE_RIM:
                        if(firstArgument.location() instanceof ASMReference ref && ref.getType() == ReferenceType.NORMAL) {
                            firstArgument = new ASMArgument(new ASMReference(ref.getName(), ReferenceType.RELATIVE_CURRENT), firstArgument.size());
                        }
                        break;
                    
                    default:
                }
                
                inst = new ASMInstruction(op, firstArgument, ei8Val, packType);
            }
        } else if(children.size() > 1) {
            // extraneous argument(s)
            logWithInvok(Level.SEVERE, children.get(1), invokNode, "Extraneous argument " + ASTUtil.detailed(children.get(1)) + " for " + opTxt);
            throw new AssemblyException();
        }
        
        // Second argument
        if(op.dgroup.hasSource && op.dgroup.hasDestination) {
            // do we have a second arg?
            if(children.size() < 3) {
                // no :(
                logWithInvok(Level.SEVERE, instNode, invokNode, "Missing second argument for " + opTxt);
                throw new AssemblyException();
            }
            
            // parse it
            secondArgument = parseArgument(children.get(2), invokNode, context);
            inst = new ASMInstruction(op, firstArgument, secondArgument, ei8Val, packType);
        } else if(children.size() > 2) {
            // extraneous argument(s)
            logWithInvok(Level.SEVERE, children.get(1), invokNode, "Extraneous argument " + ASTUtil.detailed(children.get(2)) + " for " + opTxt);
            throw new AssemblyException();
        } else if(!(op.dgroup.hasSource || op.dgroup.hasDestination)) {
            // No arguments
            inst = new ASMInstruction(op, null, null, ei8Val, packType);
        }
        
        // Log and add instruction
        if(LOG.isLoggable(Level.FINER)) {
            LOG.finer("Got instruction " + inst);
        }
        
        asmObj.addComponent(inst);
    }
    
    /**
     * Parses an argument
     * @param argNode
     * @param invokNode
     * @param context
     * @return
     * @throws AssemblyException
     */
    private static ASMArgument parseArgument(ASTNode argNode, ASTNode invokNode, ASMContext context) throws AssemblyException {
        if(LOG.isLoggable(Level.FINEST)) {
            LOG.finest("Parsing " + ASTUtil.detailed(argNode));
        }
        
        List<ASTNode> children = argNode.getChildren();
        
        // Do we have a size specifier
        int sizeOverride = 0;
        ASTNode locNode;
        
        if(children.size() > 1) {
            // We have a specifier
            sizeOverride = switch(children.get(0).getSymbol().getID()) {
                case NstassemblerLexer.ID.TERMINAL_KW_BYTE  -> 1;
                case NstassemblerLexer.ID.TERMINAL_KW_WORD  -> 2;
                case NstassemblerLexer.ID.TERMINAL_KW_PTR   -> 4;
                default -> throw new IllegalStateException("Unreachable");
            };
            
            locNode = children.get(1);
        } else {
            locNode = children.get(0);
        }
        
        // Parse location
        ASMLocation location = parseLocation(locNode, invokNode, context);
        
        // Argument
        ASMArgument arg = new ASMArgument(location, sizeOverride);
        
        if(LOG.isLoggable(Level.FINEST)) {
            LOG.finest("Got argument " + arg);
        }
        
        return arg;
    }
    
    /**
     * Parse a location - register, immediate, or memory
     * @param locNode
     * @param invokNode
     * @param context
     * @return
     * @throws AssemblyException
     */
    private static ASMLocation parseLocation(ASTNode locNode, ASTNode invokNode, ASMContext context) throws AssemblyException {
        if(LOG.isLoggable(Level.FINEST)) {
            LOG.finest("Parsing location " + ASTUtil.detailed(locNode));
        }
        
        // what do
        ASMLocation loc;
        
        switch(locNode.getSymbol().getID()) {
            case NstassemblerLexer.ID.TERMINAL_REGISTER:
                // Register
                loc = ASMRegister.fromString(locNode.getValue());
                break;
            
            case NstassemblerParser.ID.VARIABLE_MEMORY:
                // Memory
                loc = parseMemory(locNode, invokNode, context);
                break;
            
            case NstassemblerParser.ID.VARIABLE_INVOCATION:
                // Macro
                Triple<ASTNode, Integer, Integer> unTrip = unmacro(locNode, invokNode, context, true);
                ASTNode realNode = unTrip.a;
                
                loc = parseLocation(realNode, locNode, context);
                leave(unTrip, context);
                return loc;
            
            case NstassemblerLexer.ID.TERMINAL_NAME,
                 NstassemblerLexer.ID.TERMINAL_LOCAL,
                 NstassemblerLexer.ID.TERMINAL_MACROLOCAL:
                // Identifier
                unTrip = unmacro(locNode, invokNode, context, true);
                realNode = unTrip.a;
                
                if(realNode != locNode) {
                    // Was macro/substitution, handle contents
                    loc = parseLocation(realNode, locNode, context);
                    leave(unTrip, context);
                    return loc;
                } else {
                    // Actually an identifier
                    loc = parseExpression(locNode, invokNode, context);
                }
                break;
            
            default:
                // Assume it's an expression
                loc = parseExpression(locNode, invokNode, context);
        }
        
        if(LOG.isLoggable(Level.FINEST)) {
            LOG.finest("Got " + loc);
        }
        
        return loc;
    }
    
    /**
     * Parse a memory accessor
     * @param memNode
     * @param invokNode
     * @param context
     * @return
     * @throws AssemblyException
     */
    private static ASMMemory parseMemory(ASTNode memNode, ASTNode invokNode, ASMContext context) throws AssemblyException {
        if(LOG.isLoggable(Level.FINEST)) { 
            LOG.finest("Parsing " + ASTUtil.detailed(memNode));
        }
        
        // Use a stateful parser to make dealing with base/index easier
        ASMMemoryParser parser = new ASMMemoryParser();
        ASMMemory result = parser.parse(memNode.getChildren().get(0), invokNode, context);
        
        if(LOG.isLoggable(Level.FINEST)) {
            LOG.finest("Got memory " + result);
        }
        
        return result;
    }
    
    /**
     * Parse an expression
     * @param expNode
     * @param invokNode
     * @param context
     * @return
     * @throws AssemblyException
     */
    static ASMValue parseExpression(ASTNode expNode, ASTNode invokNode, ASMContext context) throws AssemblyException {
        if(LOG.isLoggable(Level.FINEST)) {
            LOG.finest("Parsing expression " + ASTUtil.detailed(expNode));
        }
        
        List<ASTNode> children = expNode.getChildren();
        
        // what do
        ASMValue value;
        
        switch(expNode.getSymbol().getID()) {
            case NstassemblerLexer.ID.TERMINAL_OP_ADD,
                 NstassemblerLexer.ID.TERMINAL_OP_AND,
                 NstassemblerLexer.ID.TERMINAL_OP_DIV,
                 NstassemblerLexer.ID.TERMINAL_OP_MOD,
                 NstassemblerLexer.ID.TERMINAL_OP_MUL,
                 NstassemblerLexer.ID.TERMINAL_OP_OR,
                 NstassemblerLexer.ID.TERMINAL_OP_SAR,
                 NstassemblerLexer.ID.TERMINAL_OP_SHL,
                 NstassemblerLexer.ID.TERMINAL_OP_SHR,
                 NstassemblerLexer.ID.TERMINAL_OP_XOR:
                // Two-argument
                value = parseTwoArgumentExpression(expNode, invokNode, context);
                break;
            
            case NstassemblerLexer.ID.TERMINAL_OP_SUB:
                // Subtraction or negation
                if(children.size() == 1) {
                    // Negation
                    value = ASMExpression.valueOf(ASMOperation.NEG, parseExpression(children.get(0), invokNode, context));
                } else {
                    // Subtraction
                    value = parseTwoArgumentExpression(expNode, invokNode, context);
                }
                break;
                
            case NstassemblerLexer.ID.TERMINAL_OP_NOT:
                // NOT
                value = ASMExpression.valueOf(ASMOperation.NOT, parseExpression(children.get(0), invokNode, context));
                break;
                
            case NstassemblerLexer.ID.TERMINAL_OP_CREL,
                 NstassemblerLexer.ID.TERMINAL_OP_PREL,
                 NstassemblerLexer.ID.TERMINAL_OP_SREL:
                // Current-instruction relative
                ReferenceType refType = switch(expNode.getSymbol().getID()) {
                    case NstassemblerLexer.ID.TERMINAL_OP_CREL  -> ReferenceType.RELATIVE_CURRENT;
                    case NstassemblerLexer.ID.TERMINAL_OP_PREL  -> ReferenceType.RELATIVE_PREVIOUS;
                    case NstassemblerLexer.ID.TERMINAL_OP_SREL  -> ReferenceType.RELATIVE_START;
                    default -> throw new IllegalStateException("Unreachable");
                };
                
                if(children.size() != 0) {
                    // Relative to an identifier
                    ASMValue id = parseExpression(children.get(0), invokNode, context);
                    
                    if(id instanceof ASMReference ref) {
                        // Valid
                        value = new ASMReference(ref.getName(), refType);
                    } else {
                        // Invalid
                        logWithInvok(Level.SEVERE, children.get(0), invokNode, "Invalid relative reference (must be identifier): " + ASTUtil.detailed(children.get(0)));
                        throw new AssemblyException();
                    }
                } else {
                    // Address value
                    value = new ASMReference("", refType);
                }
                break;
            
            case NstassemblerLexer.ID.TERMINAL_STRING:
                // String. Single characters can be used for their ascii
                String contents = expNode.getValue().substring(1, expNode.getValue().length() - 1);
                
                if(contents.length() == 1) {
                    // Valid
                    value = new ASMConstant((long) contents.charAt(0));
                } else {
                    // Invalid
                    logWithInvok(Level.SEVERE, expNode, invokNode, "Invalid string in expression: " + expNode.getValue());
                    throw new AssemblyException();
                }
                break;
            
            case NstassemblerLexer.ID.TERMINAL_INTEGER:
                // Integer. Parse it.
                value = new ASMConstant(ASTUtil.parseInteger(expNode.getValue(), 0, false));
                break;
            
            case NstassemblerLexer.ID.TERMINAL_NAME,
                 NstassemblerLexer.ID.TERMINAL_LOCAL,
                 NstassemblerLexer.ID.TERMINAL_MACROLOCAL:
                // Identifier
                Triple<ASTNode, Integer, Integer> unTrip = unmacro(expNode, invokNode, context, true); 
                ASTNode realNode = unTrip.a;
                
                if(realNode != expNode) {
                    // Identifier was a macro/substitution
                    value = parseExpression(realNode, expNode, context);
                    leave(unTrip, context);
                    return value;
                }
                
                // Actual identifier
                String unqualifiedName = expNode.getValue();
                boolean isMacroLabel = unqualifiedName.startsWith("%%");
                boolean isLocal = unqualifiedName.startsWith(".");
                boolean isTopLevel = !(isMacroLabel || isLocal);
                
                if(unqualifiedName.equals("%%i")) {
                    // %%i is special
                    value = new ASMReference("%%i", ReferenceType.NORMAL);
                } else {
                    value = new ASMReference((isTopLevel ? "" : context.getEnclosingName(isMacroLabel)) + unqualifiedName, ReferenceType.NORMAL);
                }
                break;
            
            default:
                logWithInvok(Level.SEVERE, expNode, invokNode, "Unexpected node as expression: " + ASTUtil.detailed(expNode));
                throw new AssemblyException();
        }
        
        // Report & return
        if(LOG.isLoggable(Level.FINEST)) {
            LOG.finest("Got value " + value);
        }
        
        return value;
    }
    
    /**
     * Parse a two-argument expression
     * @param expNode
     * @param invokNode
     * @param context
     * @return
     * @throws AssemblyException
     */
    private static ASMValue parseTwoArgumentExpression(ASTNode expNode, ASTNode invokNode, ASMContext context) throws AssemblyException {
        List<ASTNode> children = expNode.getChildren();
        
        ASMValue left = parseExpression(children.get(0), invokNode, context),
                 right = parseExpression(children.get(1), invokNode, context);
        
        ASMOperation op = switch(expNode.getSymbol().getID()) {
            case NstassemblerLexer.ID.TERMINAL_OP_ADD   -> ASMOperation.ADD;
            case NstassemblerLexer.ID.TERMINAL_OP_AND   -> ASMOperation.AND;
            case NstassemblerLexer.ID.TERMINAL_OP_DIV   -> ASMOperation.DIV;
            case NstassemblerLexer.ID.TERMINAL_OP_MOD   -> ASMOperation.MOD;
            case NstassemblerLexer.ID.TERMINAL_OP_MUL   -> ASMOperation.MUL;
            case NstassemblerLexer.ID.TERMINAL_OP_OR    -> ASMOperation.OR;
            case NstassemblerLexer.ID.TERMINAL_OP_SAR   -> ASMOperation.SAR;
            case NstassemblerLexer.ID.TERMINAL_OP_SHL   -> ASMOperation.SHL;
            case NstassemblerLexer.ID.TERMINAL_OP_SHR   -> ASMOperation.SHR;
            case NstassemblerLexer.ID.TERMINAL_OP_SUB   -> ASMOperation.SUB;
            case NstassemblerLexer.ID.TERMINAL_OP_XOR   -> ASMOperation.XOR;
            default -> throw new IllegalStateException("Unreachable");
        };
        
        return ASMExpression.valueOf(op, left, right);
    }
    
    /**
     * Apply macro substitutions to a node that may be a macro invocation
     * @param potentiallyMacro
     * @param invokNode
     * @param context
     * @param unwrapSingleLine
     * @return Triple<real node, #context pushes, substitution depth>
     * @throws AssemblyException
     */
    static Triple<ASTNode, Integer, Integer> unmacro(ASTNode potentiallyMacro, ASTNode invokNode, ASMContext context, boolean unwrapSingleLine) throws AssemblyException {
        List<ASTNode> children = potentiallyMacro.getChildren();
        
        switch(potentiallyMacro.getSymbol().getID()) {
            case NstassemblerParser.ID.VARIABLE_INVOCATION:
                if(LOG.isLoggable(Level.FINEST)) {
                    LOG.finest("Applying macro invocation " + ASTUtil.detailed(potentiallyMacro, 1));
                }
                
                // Macro invocation
                String name = children.get(0).getValue();
                
                // Does the name exist
                if(context.isMacro(name)) {
                    // yes
                    ASMMacro macro = context.getMacro(name);
                    context.pushContext(name);
                    
                    // Substitute arguments
                    List<ASTNode> argNodes = children.get(1).getChildren();
                    List<String> argNames = macro.getArgNames();
                    
                    // Check #args
                    if(argNames.size() != argNodes.size()) {
                        logWithInvok(Level.SEVERE, children.get(1), invokNode, "Incorrect number of arguments for " + name + ". Expected " + argNames.size() + ", got " + argNodes.size());
                        throw new AssemblyException();
                    }
                    
                    // Gather substitutions
                    for(int i = 0; i < argNames.size(); i++) {
                        context.addSubstitution(argNames.get(i), argNodes.get(i));
                    }
                    
                    // Return macro contents
                    ASTNode macroContents = macro.getContents();
                    
                    if(unwrapSingleLine && macroContents.getSymbol().getID() == NstassemblerParser.ID.VARIABLE_LINE) {
                        // unwrap
                        Triple<ASTNode, Integer, Integer> unTrip = unmacro(macroContents.getChildren().get(0), potentiallyMacro, context, unwrapSingleLine);
                        return new Triple<>(unTrip.a, unTrip.b + 1, unTrip.c);
                    } else {
                        // No unwrap
                        Triple<ASTNode, Integer, Integer> unTrip = unmacro(macroContents, potentiallyMacro, context, unwrapSingleLine);
                        return new Triple<>(unTrip.a, unTrip.b + 1, unTrip.c);
                    }
                } else {
                    // no
                    logWithInvok(Level.SEVERE, children.get(0), invokNode, name + " is not a macro, but was invoked as one");
                    throw new AssemblyException();
                }
            
            case NstassemblerLexer.ID.TERMINAL_NAME,
                 NstassemblerLexer.ID.TERMINAL_LOCAL,
                 NstassemblerLexer.ID.TERMINAL_MACROLOCAL:
                // Identifers
                name = potentiallyMacro.getValue();
                
                if(LOG.isLoggable(Level.FINEST)) {
                    LOG.finest("Retrieving identifier " + name);
                }
                
                // Is it a macro
                if(context.isMacro(name)) {
                    // It's a macro
                    ASMMacro macro = context.getMacro(name);
                    
                    // Since we're converting an identifier, it must have no arguments
                    if(macro.getArgNames().size() != 0) {
                        logWithInvok(Level.SEVERE, potentiallyMacro, invokNode, "Macro " + name + " has arguments, but was not invoked with any");
                        throw new AssemblyException();
                    }
                    
                    // Otherwise, return contents
                    ASTNode macroContents = macro.getContents();
                    
                    if(unwrapSingleLine && macroContents.getSymbol().getID() == NstassemblerParser.ID.VARIABLE_LINE) {
                        // unwrap
                        return unmacro(macroContents.getChildren().get(0), potentiallyMacro, context, unwrapSingleLine);
                    } else {
                        // No unwrap
                        return unmacro(macroContents, potentiallyMacro, context, unwrapSingleLine);
                    }
                } else if(context.isSubstituted(name)) { // not a macro. Is it substituted?
                    // It's substituted
                    Triple<ASTNode, Integer, Integer> unTrip = unmacro(context.getSubstitution(name), potentiallyMacro, context, unwrapSingleLine);
                    return new Triple<>(unTrip.a, unTrip.b, unTrip.c + 1);
                } else {
                    // Neither a macro nor substituted
                    return new Triple<>(potentiallyMacro, 0, 0);
                }
                
            
            default:
                // Not an identifier or invocation
                return new Triple<>(potentiallyMacro, 0, 0);
        }
    }
    
    /**
     * Leave macro context according to unTrip
     * @param unTrip
     */
    public static void leave(Triple<ASTNode, Integer, Integer> unTrip, ASMContext context) {
        context.leave(unTrip.b, unTrip.c);
    }
    
    /**
     * Parse a multi line macro
     * @param node
     * @param context
     */
    private static void parseMultiLineMacro(ASTNode node, ASMContext context) {
        if(LOG.isLoggable(Level.FINEST)) {
            LOG.finest("Parsing " + ASTUtil.detailed(node));
        }
        
        // Parse head
        List<ASTNode> children = node.getChildren();
        context.addMacro(parseMacroHead(children.get(0), children.get(1)));
    }
    
    /**
     * Parse a single line macro
     * @param node
     * @param context
     */
    private static void parseSingleLineMacro(ASTNode node, ASMContext context) {
        if(LOG.isLoggable(Level.FINEST)) {
            LOG.finest("Parsing " + ASTUtil.detailed(node));
        }
        
        // Parse head
        List<ASTNode> children = node.getChildren();
        context.addMacro(parseMacroHead(children.get(0), children.get(1)));
    }
    
    /**
     * Parse the head of a macro
     * @param head
     * @param contents
     * @return
     */
    private static ASMMacro parseMacroHead(ASTNode head, ASTNode contents) {
        if(LOG.isLoggable(Level.FINEST)) {
            LOG.finest("Parsing " + ASTUtil.detailed(head, 1));
        }
        
        List<ASTNode> headChildren = head.getChildren();
        
        // Parts
        String name = headChildren.get(0).getValue();
        List<ASTNode> argNodes = headChildren.get(1).getChildren();
        
        List<String> argNames = new ArrayList<>(argNodes.size());
        argNodes.forEach(n -> argNames.add(n.getValue()));
        
        if(LOG.isLoggable(Level.FINER)) {
            LOG.finer("Defined " + name + argNames + " = " + ASTUtil.detailed(contents, 1));
        }
        
        // As macro
        return new ASMMacro(name, argNames, contents);
    }
    
    /**
     * Parse a %define macro
     * @param node
     * @param context
     */
    private static void parseDefinitionMacro(ASTNode node, ASMContext context) {
        if(LOG.isLoggable(Level.FINEST)) { 
            LOG.finest("Parsing " + ASTUtil.detailed(node));
        }
        
        List<ASTNode> children = node.getChildren();
        
        // Components
        String name = children.get(0).getValue();
        ASTNode contents = children.get(1);
        
        if(LOG.isLoggable(Level.FINER)) {
            LOG.finer("Defined " + name + " = " + ASTUtil.detailed(contents));
        }
        
        // Assign to macro
        context.addMacro(new ASMMacro(name, List.of(), contents));
    }
    
    /**
     * LOG with source information for node and invoking node
     * @param level
     * @param sourceNode
     * @param invokNode
     * @param message
     */
    static void logWithInvok(Level level, ASTNode sourceNode, ASTNode invokNode, String message) {
        if(LOG.isLoggable(level)) {
            LOG.log(level, ASTUtil.getSourceInfoString(sourceNode) + (invokNode == null ? " " : " invoked by " + ASTUtil.getSourceInfoString(invokNode) + ": ") + message);
        }
    }
    
}

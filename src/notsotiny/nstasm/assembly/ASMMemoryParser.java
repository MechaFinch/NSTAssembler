package notsotiny.nstasm.assembly;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import fr.cenotelie.hime.redist.ASTNode;
import notsotiny.lib.data.Triple;
import notsotiny.lib.util.ASTUtil;
import notsotiny.nstasm.AssemblyException;
import notsotiny.nstasm.asmparts.ASMMemory;
import notsotiny.nstasm.asmparts.ASMRegister;
import notsotiny.nstasm.asmparts.ASMValue;
import notsotiny.nstasm.parser.NstassemblerLexer;
import notsotiny.sim.Register;
import notsotiny.nstasm.asmparts.ASMConstant;
import notsotiny.nstasm.asmparts.ASMExpression;
import notsotiny.nstasm.asmparts.ASMExpression.ASMOperation;
import notsotiny.nstasm.asmparts.ASMLocation;

/**
 * A stateful parser for the expression in an ASMMemory
 */
public class ASMMemoryParser {
    
    private static Logger LOG = Logger.getLogger(ASMMemoryParser.class.getName());
    
    public ASMRegister base, index;
    
    public ASMMemoryParser() {
        this.base = null;
        this.index = null;
    }
    
    /**
     * Parses an expression with this parser
     * @param memExpNode
     * @param invokNode
     * @param context
     * @return
     * @throws AssemblyException
     */
    public ASMMemory parse(ASTNode memExpNode, ASTNode invokNode, ASMContext context) throws AssemblyException {
        ASMLocation parsed = parsePart(memExpNode, invokNode, context);
        
        // Wrap if not wrapped
        return switch(parsed) {
            case ASMRegister reg    -> { // one reg
                if(reg.reg().size() == 4) {
                    // reg is base
                    yield new ASMMemory(reg.reg(), null, null, null);
                } else {
                    // reg is index
                    yield new ASMMemory(null, reg.reg(), null, null);
                }
            }
            case ASMValue offs  -> new ASMMemory(null, null, null, offs); // offset
            case ASMMemory mem  -> mem; // already memory
            default -> throw new IllegalStateException("Unreachable");
        };
    }
    
    /**
     * Parse a memory part
     * @param memExpNode
     * @param invokNode
     * @param context
     * @return
     * @throws AssemblyException
     */
    private ASMLocation parsePart(ASTNode memExpNode, ASTNode invokNode, ASMContext context) throws AssemblyException {
        if(LOG.isLoggable(Level.FINEST)) {
            LOG.finest("Parsing memory part " + ASTUtil.detailed(memExpNode));
        }
        
        // Whaddawe doin
        List<ASTNode> children = memExpNode.getChildren();
        ASMLocation loc = null;
        
        switch(memExpNode.getSymbol().getID()) {
            
            case NstassemblerLexer.ID.TERMINAL_REGISTER:
                // Base or index
                Register r = Register.fromString(memExpNode.getValue());
                
                if(r.size() == 4) {
                    // Base
                    if(this.base != null) {
                        // Already have a base
                        ASMParser.logWithInvok(Level.SEVERE, memExpNode, invokNode, "Cannot have multiple bases: " + r + " and " + this.base);
                        throw new AssemblyException();
                    }
                    
                    this.base = ASMRegister.fromReg(r);
                    return this.base;
                } else if(r.size() == 2) {
                    // Index
                    if(this.index != null) {
                        // Already have an index
                        ASMParser.logWithInvok(Level.SEVERE, memExpNode, invokNode, "Cannot have multiple indices: " + r + " and " + this.index);
                        throw new AssemblyException();
                    }
                    
                    this.index = ASMRegister.fromReg(r);
                    return this.index;
                } else {
                    // Invalid
                    ASMParser.logWithInvok(Level.SEVERE, memExpNode, invokNode, "Invalid register as memory base/index: " + r);
                    throw new AssemblyException();
                }
            
            case NstassemblerLexer.ID.TERMINAL_OP_ADD:
                // Addition. Either side may contain registers or integeres
                ASMLocation left = parsePart(children.get(0), invokNode, context),
                            right = parsePart(children.get(1), invokNode, context);
                
                /**
                 * Each side may be
                 * ASMRegister      Just a register
                 * ASMMemory        Multiple memory components
                 * ASMValue         Just an integer
                 */
                // mmm yummy if statements
                if(left instanceof ASMRegister lr) {
                    // Left is register
                    if(lr.reg().size() == 4) {
                        // Left is base
                        if(right instanceof ASMRegister rr) {
                            // Right is index (exception otherwise)
                            loc = new ASMMemory(lr.reg(), rr.reg(), null, null);
                        } else if(right instanceof ASMValue rv) {
                            // Right is offset
                            loc = new ASMMemory(lr.reg(), null, null, rv);
                        } else if(right instanceof ASMMemory rm) {
                            // Right has multiple components
                            loc = new ASMMemory(lr.reg(), rm.getIndex(), rm.getScale(), rm.getOffset());
                        } else {
                            // Unreachable
                            throw new IllegalStateException("Unreachable");
                        }
                    } else {
                        // Left is index
                        if(right instanceof ASMRegister rr) {
                            // Right is base (exception otherwise)
                            loc = new ASMMemory(rr.reg(), lr.reg(), null, null);
                        } else if(right instanceof ASMValue rv) {
                            // Right is offset
                            loc = new ASMMemory(null, lr.reg(), null, rv);
                        } else if(right instanceof ASMMemory rm) {
                            // Right has multiple components
                            loc = new ASMMemory(rm.getBase(), lr.reg(), rm.getScale(), rm.getOffset());
                        } else {
                            // Unreachable
                            throw new IllegalStateException("Unreachable");
                        }
                    }
                } else if(left instanceof ASMValue lv) {
                    // Left is offset
                    if(right instanceof ASMRegister rr) {
                        // Right is register
                        if(rr.reg().size() == 4) {
                            // Right is base
                            loc = new ASMMemory(rr.reg(), null, null, lv);
                        } else {
                            // Right is index
                            loc = new ASMMemory(null, rr.reg(), null, lv);
                        }
                    } else if(right instanceof ASMValue rv) {
                        // Right is another part of the offset
                        loc = ASMExpression.valueOf(ASMOperation.ADD, lv, rv);
                    } else if(right instanceof ASMMemory rm) {
                        // Right has multiple components
                        loc = new ASMMemory(rm.getBase(), rm.getIndex(), rm.getScale(), ASMExpression.valueOf(ASMOperation.ADD, lv, rm.getOffset()));
                    } else {
                        // Unreachable
                        throw new IllegalStateException("Unreachable");
                    }
                } else if(left instanceof ASMMemory lm) {
                    // Left has multiple components
                    if(right instanceof ASMRegister rr) {
                        // Right is register
                        if(rr.reg().size() == 4) {
                            // Right is base
                            loc = new ASMMemory(rr.reg(), lm.getIndex(), lm.getScale(), lm.getOffset());
                        } else {
                            // Right is index
                            loc = new ASMMemory(lm.getBase(), rr.reg(), lm.getScale(), lm.getOffset());
                        }
                    } else if(right instanceof ASMValue rv) {
                        // Right is offset
                        loc = new ASMMemory(lm.getBase(), lm.getIndex(), lm.getScale(), ASMExpression.valueOf(ASMOperation.ADD, lm.getOffset(), rv));
                    } else if(right instanceof ASMMemory rm) {
                        // Right has multiple components
                        Register nb = lm.getBase() == Register.NONE ? rm.getBase() : lm.getBase();    // Only one base
                        Register ni = lm.getIndex() == Register.NONE ? rm.getIndex() : lm.getIndex(); // Only one index
                        ASMValue ns = lm.getIndex() == null ? rm.getScale() : lm.getScale();    // Scale comes with index
                        ASMValue no = ASMExpression.valueOf(ASMOperation.ADD, lm.getOffset(), rm.getOffset());
                        
                        loc = new ASMMemory(nb, ni, ns, no);
                    }
                } else {
                    // Unreachable
                    throw new IllegalStateException("Unreachable");
                }
                break;
                
            case NstassemblerLexer.ID.TERMINAL_OP_SUB:
                // Subtraction or negation
                // first present regardless
                left = parsePart(children.get(0), invokNode, context);
                
                if(children.size() == 1) {
                    // Negation. Left must be offset
                    if(left instanceof ASMValue lv) {
                        // Left is valid
                        loc = ASMExpression.valueOf(ASMOperation.NEG, lv);
                    } else {
                        // Left is invalid
                        ASMParser.logWithInvok(Level.SEVERE, children.get(0), invokNode, "Invalid value as negation in memory: " + left);
                        throw new AssemblyException();
                    }
                } else {
                    // Subtraction. Right side must be offset
                    right = parsePart(children.get(1), invokNode, context);
                    
                    if(right instanceof ASMValue rv) {
                        // Right is valid
                        ASMValue nrv = ASMExpression.valueOf(ASMOperation.NEG, rv);
                        
                        if(left instanceof ASMRegister lr) {
                            // Left is register
                            if(lr.reg().size() == 4) {
                                // Left is base
                                loc = new ASMMemory(lr.reg(), null, null, nrv);
                            } else {
                                // Left is index
                                loc = new ASMMemory(null, lr.reg(), null, nrv);
                            }
                        } else if(left instanceof ASMValue lv) {
                            // Offset is left - right
                            loc =ASMExpression.valueOf(ASMOperation.SUB, lv, rv);
                        } else if(left instanceof ASMMemory lm) {
                            // Left has multiple components
                            loc = new ASMMemory(lm.getBase(), lm.getIndex(), lm.getScale(), ASMExpression.valueOf(ASMOperation.SUB, lm.getOffset(), rv));
                        } else {
                            // Unreachable
                            throw new IllegalStateException("Unreachable");
                        }
                    } else {
                        // Right is invalid
                        ASMParser.logWithInvok(Level.SEVERE, children.get(1), invokNode, "Invalid value as subtrahend in memory: " + right);
                        throw new AssemblyException();
                    }
                }
                break;
                
            case NstassemblerLexer.ID.TERMINAL_OP_MUL:
                // Multiplication. Might be scale
                left = parsePart(children.get(0), invokNode, context);
                right = parsePart(children.get(1), invokNode, context);
                
                if(left instanceof ASMRegister lr) {
                    // Might be index*scale
                    if(lr.reg().size() == 2) {
                        // Left is index
                        if(right instanceof ASMConstant rc && (rc.getValue() == 1 || rc.getValue() == 2 || rc.getValue() == 4)) {
                            // Right is scale
                            loc = new ASMMemory(null, lr.reg(), rc, null);
                        } else {
                            // Invalid
                            ASMParser.logWithInvok(Level.SEVERE, children.get(1), invokNode, "Invalid scale: " + right);
                            throw new AssemblyException();
                        }
                    } else {
                        // Left is base, invalid
                        ASMParser.logWithInvok(Level.SEVERE, children.get(0), invokNode, "Invalid index: " + left);
                        throw new AssemblyException();
                    }
                } else if(left instanceof ASMValue) {
                    // Might be scale*index, might be multiplication
                    if(left instanceof ASMConstant lc && (lc.getValue() == 1 || lc.getValue() == 2 || lc.getValue() == 4)) {
                        // Left is a valid scale
                        if(right instanceof ASMRegister rr) {
                            // Right is a register
                            if(rr.reg().size() == 2) {
                                // Right is index
                                loc = new ASMMemory(null, rr.reg(), lc, null);
                            } else {
                                // Invlid
                                ASMParser.logWithInvok(Level.SEVERE, children.get(1), invokNode, "Invalid index: " + right);
                                throw new AssemblyException();
                            }
                        }
                    } else {
                        // Not a valid scale. Defer to expression parser
                        break;
                    }
                } else {
                    // Invalid
                    ASMParser.logWithInvok(Level.SEVERE, children.get(0), invokNode, "Invalid factor in memory: " + left);
                    throw new AssemblyException();
                }
                break;
            
            case NstassemblerLexer.ID.TERMINAL_NAME,
                 NstassemblerLexer.ID.TERMINAL_LOCAL,
                 NstassemblerLexer.ID.TERMINAL_MACROLOCAL:
                // Identifiers, may be macro
                Triple<ASTNode, Integer, Integer> unTrip = ASMParser.unmacro(memExpNode, invokNode, context, true);
                ASTNode realNode = unTrip.a;
                
                if(realNode != memExpNode) {
                    loc = parsePart(realNode, memExpNode, context);
                    ASMParser.leave(unTrip, context);
                }
                break;
            
            default:
        }
        
        // Did we get something
        if(loc != null) {
            if(LOG.isLoggable(Level.FINEST)) {
                LOG.finest("Got " + loc);
            }
            
            return loc;
        }
        
        // No, try as normal expression
        return ASMParser.parseExpression(memExpNode, invokNode, context);
    }
    
}

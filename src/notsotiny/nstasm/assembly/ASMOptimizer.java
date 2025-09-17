package notsotiny.nstasm.assembly;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import notsotiny.nstasm.ASMUtil;
import notsotiny.nstasm.AssemblyOptions;
import notsotiny.nstasm.asmparts.ASMArgument;
import notsotiny.nstasm.asmparts.ASMComponent;
import notsotiny.nstasm.asmparts.ASMConstant;
import notsotiny.nstasm.asmparts.ASMInstruction;
import notsotiny.nstasm.asmparts.ASMLabel;
import notsotiny.nstasm.asmparts.ASMMemory;
import notsotiny.nstasm.asmparts.ASMObject;
import notsotiny.nstasm.asmparts.ASMRegister;
import notsotiny.nstasm.asmparts.ASMRepetition;
import notsotiny.nstasm.asmparts.ASMValue;
import notsotiny.sim.Register;
import notsotiny.sim.ops.Opcode;

public class ASMOptimizer {
    
    private static Logger LOG = Logger.getLogger(ASMOptimizer.class.getName());
    
    /**
     * Performs width optimizations on the given object. This operation is performed in-place
     * @param object
     * @param immediates
     * @param options
     * @return true if any changes were made
     */
    public static boolean optimizeWidth(ASMObject object, MultiList<ResolvedImmediate> immediates, AssemblyOptions options) {
        LOG.finer("Optimizing width");
        
        // Respect options
        if(!options.optimizeInstructionWidth() || !object.allowsLengthOptimization()) {
            return false;
        }
        
        // Process each component
        int i = 0;
        boolean changed = false;
        
        for(ASMComponent c : object.getComponents()) {
            if(c instanceof ASMLabel) {
                continue;
            } else if(c instanceof ASMRepetition) {
                // TODO: handle inside repetitions?
            } else if(c instanceof ASMInstruction inst) {
                // Something we can actually optimize
                ResolvedImmediate imm = immediates.getSingle(List.of(i));
                changed |= tryApplyShortcut(inst, imm);
                changed |= tryShortenImmediate(inst, imm);
            }
            
            i += 1;
        }
        
        return changed;
    }
    
    private enum LocType {
        REGISTER, IMMEDIATE, MEMORY, NONE
    }
    
    private static LocType typeOf(ASMArgument arg) {
        return arg == null ? LocType.NONE : switch(arg.location()) {
            case ASMRegister _  -> LocType.REGISTER;
            case ASMMemory _    -> LocType.MEMORY;
            case ASMValue _     -> LocType.IMMEDIATE;
            default             -> LocType.NONE;
        };
    }
    
    /**
     * Attempt to convert to a shortcut opcode
     * @param i
     * @param imm
     * @return
     */
    private static boolean tryApplyShortcut(ASMInstruction i, ResolvedImmediate imm) {
        if(LOG.isLoggable(Level.FINEST)) {
            //LOG.finest("Attempting to apply shortcuts to " + i + " with immediate value " + imm);
        }
        
        /*
         * Generally speaking, comments are formatted
         * SHORTCUT_OPCODE  Pattern     Bytes saved per instruction
         * Pattern reflects valid arguments for CURRENT_OPCODE, not SHORTCUT_OPCODE
         * 
         * Note also that imm may be an immediate source or memory offset
         */
        
        int signedWidth = ASMUtil.getWidth(imm.value(), true, true, true, false),
            unsignedWidth = ASMUtil.getWidth(imm.value(), false, false, true, false);
        
        LocType sourceType = typeOf(i.getSource());
        
        switch(i.getOp()) {
            case MOVW_RIM:
                /*
                 * MOVW_RIM_0   MOVW reg, 0             2-4
                 * MOVW_RIM_BP  MOVW reg, [BP + i8]     1
                 * MOVW_BP_RIM  MOVW [BP + i8], reg     1
                 * MOVZ_RIM     MOVW r32/r16, u16/u8    1-2
                 * MOVS_RIM     MOVW r32/r16, i16/i8    1-2
                 */
                if(imm.resolved()) {
                    if(sourceType == LocType.IMMEDIATE && imm.value() == 0) {
                        // MOVW ??, 0 -> MOVW_RIM_0
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut MOVW_RIM_0 to " + i);
                        
                        i.setOp(Opcode.MOVW_RIM_0);
                        return true;
                    } else if(sourceType == LocType.IMMEDIATE && signedWidth < ((ASMRegister) i.getDestination().location()).reg().size()) {
                        // MOVS
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut MOVS_RIM to " + i);
                        
                        i.setOp(Opcode.MOVS_RIM);
                        return true;
                    } else if(sourceType == LocType.IMMEDIATE && unsignedWidth < ((ASMRegister) i.getDestination().location()).reg().size()) {
                        // MOVZ
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut MOVZ_RIM to " + i);
                        
                        i.setOp(Opcode.MOVZ_RIM);
                        return true;
                    } else if(i.getSource().location() instanceof ASMMemory ms) {
                        // MOVW ??, [??]
                        if(ms.getBase() == Register.BP && ms.getIndex() == Register.NONE && signedWidth == 1) {
                            // MOVW ??, [BP + i8]
                            if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut MOVW_RIM_BP to " + i);
                            
                            i.setOp(Opcode.MOVW_RIM_BP);
                            i.setEI8(ms.getOffset());
                            return true;
                        }
                    } else if(i.getDestination().location() instanceof ASMMemory md) {
                        // MOVW [??], ??
                        if(md.getBase() == Register.BP && md.getIndex() == Register.NONE && signedWidth == 1) {
                            // MOVW [BP + i8], ??
                            if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut MOVW_BP_RIM to " + i);
                            
                            i.setOp(Opcode.MOVW_BP_RIM);
                            i.setEI8(md.getOffset());
                            return true;
                        }
                    }
                }
                return false;
                
            case MOVS_RIM:
                /*
                 * MOVS_<R16>_I8    MOVS r16, i8    1
                 */
                if(sourceType == LocType.IMMEDIATE && i.getDestination().location() instanceof ASMRegister ar &&
                   ar.reg().size() == 2 && imm.resolved() && signedWidth == 1) {
                    // MOVS r16, i8
                    Opcode newOp = switch(ar.reg()) {
                        case A  -> Opcode.MOVS_A_I8;
                        case B  -> Opcode.MOVS_B_I8;
                        case C  -> Opcode.MOVS_C_I8;
                        case D  -> Opcode.MOVS_D_I8;
                        case I  -> Opcode.MOVS_I_I8;
                        case J  -> Opcode.MOVS_J_I8;
                        case K  -> Opcode.MOVS_K_I8;
                        case L  -> Opcode.MOVS_L_I8;
                        default -> Opcode.MOVS_RIM;  // Unreachable if args validated correctly
                    };
                    
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut " + newOp + " to " + i);
                    
                    i.setOp(newOp);
                    return true;
                }
                return false;
            
            case MOV_RIM:
                /*
                 * MOV_<R16>_I16    MOV r16, i16        1
                 * MOVS_<R16>_I8    MOV r16, i8         2
                 * MOV_RIM_BP       MOV reg, [BP + i8]  1
                 * MOV_BP_RIM       MOV [BP + i8], reg  1
                 */
                if(sourceType == LocType.IMMEDIATE && i.getDestination().location() instanceof ASMRegister ar &&
                   ar.reg().size() == 2) {
                    boolean small = imm.resolved() && signedWidth == 1 && i.getSource().size() < 2;
                    
                    // MOV r16, imm
                    Opcode newOp = switch(ar.reg()) {
                        case A  -> small ? Opcode.MOVS_A_I8 : Opcode.MOV_A_I16;
                        case B  -> small ? Opcode.MOVS_B_I8 : Opcode.MOV_B_I16;
                        case C  -> small ? Opcode.MOVS_C_I8 : Opcode.MOV_C_I16;
                        case D  -> small ? Opcode.MOVS_D_I8 : Opcode.MOV_D_I16;
                        case I  -> small ? Opcode.MOVS_I_I8 : Opcode.MOV_I_I16;
                        case J  -> small ? Opcode.MOVS_J_I8 : Opcode.MOV_J_I16;
                        case K  -> small ? Opcode.MOVS_K_I8 : Opcode.MOV_K_I16;
                        case L  -> small ? Opcode.MOVS_L_I8 : Opcode.MOV_L_I16;
                        default -> Opcode.MOV_RIM;  // Unreachable if args validated correctly
                    };
                    
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut " + newOp + " to " + i);
                    
                    i.setOp(newOp);
                    return true;
                }  else if(i.getSource().location() instanceof ASMMemory ms) {
                    // MOVW ??, [??]
                    if(ms.getBase() == Register.BP && ms.getIndex() == Register.NONE && signedWidth == 1) {
                        // MOVW ??, [BP + i8]
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut MOV_RIM_BP to " + i);
                        
                        i.setOp(Opcode.MOV_RIM_BP);
                        i.setEI8(ms.getOffset());
                        return true;
                    }
                } else if(i.getDestination().location() instanceof ASMMemory md) {
                    // MOVW [??], ??
                    if(md.getBase() == Register.BP && md.getIndex() == Register.NONE && signedWidth == 1) {
                        // MOVW [BP + i8], ??
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut MOV_BP_RIM to " + i);
                        
                        i.setOp(Opcode.MOV_BP_RIM);
                        i.setEI8(md.getOffset());
                        return true;
                    }
                }
                return false;
            
            case PUSH_RIM:
                /*
                 * PUSH_<R16>   PUSH r16    1
                 */
                if(i.getSource().location() instanceof ASMRegister ar && ar.reg().size() == 2) {
                    // PUSH r16
                    Opcode newOp = switch(ar.reg()) {
                        case A  -> Opcode.PUSH_A;
                        case B  -> Opcode.PUSH_B;
                        case C  -> Opcode.PUSH_C;
                        case D  -> Opcode.PUSH_D;
                        case I  -> Opcode.PUSH_I;
                        case J  -> Opcode.PUSH_J;
                        case K  -> Opcode.PUSH_K;
                        case L  -> Opcode.PUSH_L;
                        default -> Opcode.PUSH_RIM;  // Unreachable if args validated correctly
                    };
                    
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut " + newOp + " to " + i);
                    
                    i.setOp(newOp);
                    return true;
                }
                return false;
                
            case PUSHW_RIM:
                /*
                 * PUSHW_<R32>  PUSH r32    1
                 */
                if(i.getSource().location() instanceof ASMRegister ar && ar.reg().size() == 4) {
                    //PUSHW r32
                    Opcode newOp = switch(ar.reg()) {
                        case DA -> Opcode.PUSHW_DA;
                        case BC -> Opcode.PUSHW_BC;
                        case JI -> Opcode.PUSHW_JI;
                        case LK -> Opcode.PUSHW_LK;
                        case XP -> Opcode.PUSHW_XP;
                        case YP -> Opcode.PUSHW_YP;
                        case BP -> Opcode.PUSHW_BP;
                        default -> Opcode.PUSHW_RIM;
                    };
                    
                    // PUSHW SP is valid, if unlikely to be useful
                    if(newOp != Opcode.PUSHW_RIM) {
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut " + newOp + " to " + i);
                        
                        i.setOp(newOp);
                        return true;
                    }
                }
                return false;
                
            case POP_RIM:
                /*
                 * POP_<R16>    POP r16     1
                 */
                if(i.getDestination().location() instanceof ASMRegister ar && ar.reg().size() == 2) {
                    // POP r16
                    Opcode newOp = switch(ar.reg()) {
                        case A  -> Opcode.POP_A;
                        case B  -> Opcode.POP_B;
                        case C  -> Opcode.POP_C;
                        case D  -> Opcode.POP_D;
                        case I  -> Opcode.POP_I;
                        case J  -> Opcode.POP_J;
                        case K  -> Opcode.POP_K;
                        case L  -> Opcode.POP_L;
                        default -> Opcode.POP_RIM;  // Unreachable if args validated correctly
                    };
                    
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut " + newOp + " to " + i);
                    
                    i.setOp(newOp);
                    return true;
                }
                return false;
            
            case POPW_RIM:
                /*
                 * POPW_<R32>   POPW r32    1
                 */
                if(i.getDestination().location() instanceof ASMRegister ar && ar.reg().size() == 4) {
                    // POPW r32
                    Opcode newOp = switch(ar.reg()) {
                        case DA -> Opcode.POPW_DA;
                        case BC -> Opcode.POPW_BC;
                        case JI -> Opcode.POPW_JI;
                        case LK -> Opcode.POPW_LK;
                        case XP -> Opcode.POPW_XP;
                        case YP -> Opcode.POPW_YP;
                        case BP -> Opcode.POPW_BP;
                        default -> Opcode.POPW_RIM;
                    };
                    
                    // PUSHW SP is valid, if unlikely to be useful
                    if(newOp != Opcode.POPW_RIM) {
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut " + newOp + " to " + i);
                        
                        i.setOp(newOp);
                        return true;
                    }
                }
                return false;
            
            case CMP_RIM:
                /*
                 * CMP_RIM_0    CMP reg, 0  1-2
                 * CMP_RIM_I8   CMP reg, i8 1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE) {
                    if(imm.value() == 0) {
                        // CMP_RIM_0
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut CMP_RIM_0 to " + i);
                        
                        i.setOp(Opcode.CMP_RIM_0);
                        return true;
                    } else if(signedWidth == 1) {
                        // CMP_RIM_I8
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut CMP_RIM_I8 to " + i);
                        
                        i.setOp(Opcode.CMP_RIM_I8);
                        i.setEI8((ASMValue) i.getSource().location());
                        return true;
                    }
                }
                return false;
                
            case CMPW_RIM:
                /*
                 * CMPW_RIM_0   CMPW reg, 0     2-4
                 * CMPW_RIM_I8  CMPW reg, i8    1-3
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE) {
                    if(imm.value() == 0) {
                        // CMP_RIM_0
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut CMPW_RIM_0 to " + i);
                        
                        i.setOp(Opcode.CMPW_RIM_0);
                        return true;
                    } else if(signedWidth == 1) {
                        // CMP_RIM_I8
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut CMPW_RIM_I8 + to " + i);
                        
                        i.setOp(Opcode.CMPW_RIM_I8);
                        i.setEI8((ASMValue) i.getSource().location());
                        return true;
                    }
                }
                return false;
                
            case ADD_RIM:
                /*
                 * INC_RIM          ADD x, 1        1-2
                 * ADD_<R16>_I8     ADD r16, i8     2
                 * ADD_RIM_I8       all useful cases caught by ADD_<R16>_I8
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && unsignedWidth == 1) {
                    Opcode newOp = Opcode.INVALID;
                    
                    if(imm.value() == 1) {
                        // INC_RIM
                        newOp = Opcode.INC_RIM;
                    } else {
                        // ADD_<R16>_I8
                        newOp = switch(((ASMRegister) i.getDestination().location()).reg()) {
                            case A  -> Opcode.ADD_A_I8;
                            case B  -> Opcode.ADD_B_I8;
                            case C  -> Opcode.ADD_C_I8;
                            case D  -> Opcode.ADD_D_I8;
                            case I  -> Opcode.ADD_I_I8;
                            case J  -> Opcode.ADD_J_I8;
                            case K  -> Opcode.ADD_K_I8;
                            case L  -> Opcode.ADD_L_I8;
                            default -> Opcode.ADD_RIM_I8;
                        };
                    }
                    
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut " + newOp + " to " + i);
                    
                    i.setOp(newOp);
                    i.setEI8((ASMValue) i.getSource().location());
                    return true;
                }
                return false;
                
            case ADD_RIM_I8:
                /*
                 * INC_RIM          ADD x, 1        1
                 * ADD_<R16>_I8     ADD r16, i8     1
                 */
                if(i.getEI8() instanceof ASMConstant c && c.getValue() == 1) {
                    if(LOG.isLoggable(Level.FINEST))LOG.finest("Applied shortcut INC_RIM to " + i);
                    
                    i.setOp(Opcode.INC_RIM);
                    return true;
                } else if(i.getDestination().location() instanceof ASMRegister ar && ar.reg().size() == 2) {
                    // ADD_<R16>_I8
                    Opcode newOp = switch(ar.reg()) {
                        case A  -> Opcode.ADD_A_I8;
                        case B  -> Opcode.ADD_B_I8;
                        case C  -> Opcode.ADD_C_I8;
                        case D  -> Opcode.ADD_D_I8;
                        case I  -> Opcode.ADD_I_I8;
                        case J  -> Opcode.ADD_J_I8;
                        case K  -> Opcode.ADD_K_I8;
                        case L  -> Opcode.ADD_L_I8;
                        default -> Opcode.ADD_RIM_I8; // should be unreachable with validated args
                    };

                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut " + newOp + " to " + i);
                    
                    i.setOp(newOp);
                    i.setSource(new ASMArgument(i.getEI8(), 1));
                    return true;
                }
                return false;
                
            case ADDW_RIM:
                /*
                 * INCW_RIM         ADDW x, 1       4
                 * ADDW_<R32>_I8    ADDW r32, i8    4
                 * ADDW_RIM_I8      all useful cases caught by ADDW_<R32>_I8
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && unsignedWidth == 1) {
                    Opcode newOp = Opcode.INVALID;
                    
                    if(imm.value() == 1) {
                        newOp = Opcode.INCW_RIM;
                    } else {
                        newOp = switch(((ASMRegister) i.getDestination().location()).reg()) {
                            case DA -> Opcode.ADDW_DA_I8;
                            case BC -> Opcode.ADDW_BC_I8;
                            case JI -> Opcode.ADDW_JI_I8;
                            case LK -> Opcode.ADDW_LK_I8;
                            case XP -> Opcode.ADDW_XP_I8;
                            case YP -> Opcode.ADDW_YP_I8;
                            case BP -> Opcode.ADDW_BP_I8;
                            case SP -> Opcode.ADDW_SP_I8;
                            default -> Opcode.ADDW_RIM_I8;
                        };
                    }
                    
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut " + newOp + " to " + i);
                    
                    i.setOp(newOp);
                    i.setEI8((ASMValue) i.getSource().location());
                    return true;
                }
                return false;
                
            case ADDW_RIM_I8:
                /*
                 * INC_RIM          ADDW x, 1       1
                 * ADDW_<R16>_I8    ADDW r16, i8    1
                 */
                if(i.getEI8() instanceof ASMConstant c && c.getValue() == 1) {
                    // INC_RIM
                    if(LOG.isLoggable(Level.FINEST))LOG.finest("Applied shortcut INCW_RIM to " + i);
                    
                    i.setOp(Opcode.INC_RIM);
                    return true;
                } else if(i.getDestination().location() instanceof ASMRegister ar && ar.reg().size() == 4) {
                    // ADD_<R16>_I8
                    Opcode newOp = switch(ar.reg()) {
                        case DA -> Opcode.ADDW_DA_I8;
                        case BC -> Opcode.ADDW_BC_I8;
                        case JI -> Opcode.ADDW_JI_I8;
                        case LK -> Opcode.ADDW_LK_I8;
                        case XP -> Opcode.ADDW_XP_I8;
                        case YP -> Opcode.ADDW_YP_I8;
                        case BP -> Opcode.ADDW_BP_I8;
                        case SP -> Opcode.ADDW_SP_I8;
                        default -> Opcode.ADDW_RIM_I8; // should be unreachable with validated args
                    };
                    
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut " + newOp + " to " + i);
                    
                    i.setOp(newOp);
                    i.setSource(new ASMArgument(i.getEI8(), 1));
                    return true;
                }
                return false;
            
            case SUB_RIM:
                /*
                 * DEC_RIM          SUB x, 1        1-2
                 * SUB_<R16>_I8     SUB r16, i8     2
                 * SUB_RIM_I8       all useful cases caught by SUB_<R16>_I8
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && unsignedWidth == 1) {
                    Opcode newOp = Opcode.INVALID;
                    
                    if(imm.value() == 1) {
                        // DEC_RIM
                        newOp = Opcode.DEC_RIM;
                    } else {
                        // SUB_<R16>_I8
                        newOp = switch(((ASMRegister) i.getDestination().location()).reg()) {
                            case A  -> Opcode.SUB_A_I8;
                            case B  -> Opcode.SUB_B_I8;
                            case C  -> Opcode.SUB_C_I8;
                            case D  -> Opcode.SUB_D_I8;
                            case I  -> Opcode.SUB_I_I8;
                            case J  -> Opcode.SUB_J_I8;
                            case K  -> Opcode.SUB_K_I8;
                            case L  -> Opcode.SUB_L_I8;
                            default -> Opcode.SUB_RIM_I8;
                        };
                    }
                    
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut " + newOp + " to " + i);
                    
                    i.setOp(newOp);
                    i.setEI8((ASMValue) i.getSource().location());
                    return true;
                }
                return false;
                
            case SUB_RIM_I8:
                /*
                 * DEC_RIM          SUB x, 1        1
                 * SUB_<R16>_I8     SUB r16, i8     1
                 */
                if(i.getEI8() instanceof ASMConstant c && c.getValue() == 1) {
                    // DEC_RIM
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut DEC_RIM to " + i);
                    
                    i.setOp(Opcode.DEC_RIM);
                    return true;
                } else if(i.getDestination().location() instanceof ASMRegister ar && ar.reg().size() == 2) {
                    // SUB_<R16>_I8
                    Opcode newOp = switch(ar.reg()) {
                        case A  -> Opcode.SUB_A_I8;
                        case B  -> Opcode.SUB_B_I8;
                        case C  -> Opcode.SUB_C_I8;
                        case D  -> Opcode.SUB_D_I8;
                        case I  -> Opcode.SUB_I_I8;
                        case J  -> Opcode.SUB_J_I8;
                        case K  -> Opcode.SUB_K_I8;
                        case L  -> Opcode.SUB_L_I8;
                        default -> Opcode.SUB_RIM_I8; // should be unreachable with validated args
                    };
                    
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut " + newOp + " to " + i);
                    
                    i.setOp(newOp);
                    i.setSource(new ASMArgument(i.getEI8(), 1));
                    return true;
                }
                return false;
                
            case SUBW_RIM:
                /*
                 * DECW_RIM         SUBW x, 1       4
                 * SUBW_<R32>_I8    SUBW r32, i8    4
                 * SUBW_RIM_I8      all useful cases caught by SUBW_<R32>_I8
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && unsignedWidth == 1) {
                    Opcode newOp = Opcode.INVALID;
                    
                    if(imm.value() == 1) {
                        // DECW_RIM
                        newOp = Opcode.DECW_RIM;
                    } else {
                        // SUBW_<R32>_I8
                        newOp = switch(((ASMRegister) i.getDestination().location()).reg()) {
                            case DA -> Opcode.SUBW_DA_I8;
                            case BC -> Opcode.SUBW_BC_I8;
                            case JI -> Opcode.SUBW_JI_I8;
                            case LK -> Opcode.SUBW_LK_I8;
                            case XP -> Opcode.SUBW_XP_I8;
                            case YP -> Opcode.SUBW_YP_I8;
                            case BP -> Opcode.SUBW_BP_I8;
                            case SP -> Opcode.SUBW_SP_I8;
                            default -> Opcode.SUBW_RIM_I8;
                        };
                    }
                    
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut " + newOp + " to " + i);
                    
                    i.setOp(newOp);
                    i.setEI8((ASMValue) i.getSource().location());
                    return true;
                }
                return false;             
                
            case SUBW_RIM_I8:
                /*
                 * DECW_RIM         SUBW x, 1       1
                 * SUBW_<R16>_I8    SUB r16, i8     1
                 */
                if(i.getEI8() instanceof ASMConstant c && c.getValue() == 1) {
                    // DECW_RIM
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut DECW_RIM to " + i);
                    
                    i.setOp(Opcode.DECW_RIM);
                    return true;
                } else if(i.getDestination().location() instanceof ASMRegister ar && ar.reg().size() == 4) {
                    // SUB_<R16>_I8
                    Opcode newOp = switch(ar.reg()) {
                        case DA -> Opcode.SUBW_DA_I8;
                        case BC -> Opcode.SUBW_BC_I8;
                        case JI -> Opcode.SUBW_JI_I8;
                        case LK -> Opcode.SUBW_LK_I8;
                        case XP -> Opcode.SUBW_XP_I8;
                        case YP -> Opcode.SUBW_YP_I8;
                        case BP -> Opcode.SUBW_BP_I8;
                        case SP -> Opcode.SUBW_SP_I8;
                        default -> Opcode.SUBW_RIM_I8; // should be unreachable with validated args
                    };
                    
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut " + newOp + " to " + i);
                    
                    i.setOp(newOp);
                    i.setSource(new ASMArgument(i.getEI8(), 1));
                    return true;
                }
                return false;
                
            case SHL_RIM:
                /*
                 * SHL_RIM_1    SHL reg, 1  1-2
                 * SHL_RIM_I8   SHL reg, i8 1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE) {
                    if(imm.value() == 1) {
                        // SHL_RIM_0
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut SHL_RIM_1 to " + i);
                        
                        i.setOp(Opcode.SHL_RIM_1);
                        return true;
                    } else if(signedWidth == 1) {
                        // SHL_RIM_I8
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut SHL_RIM_I8 to " + i);
                        
                        i.setOp(Opcode.SHL_RIM_I8);
                        i.setEI8((ASMValue) i.getSource().location());
                        return true;
                    }
                }
                return false;
            
            case SHR_RIM:
                /*
                 * SHR_RIM_1    SHR reg, 1  1-2
                 * SHR_RIM_I8   SHR reg, i8 1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE) {
                    if(imm.value() == 1) {
                        // SHR_RIM_0
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut SHR_RIM_1 to " + i);
                        
                        i.setOp(Opcode.SHR_RIM_1);
                        return true;
                    } else if(signedWidth == 1) {
                        // SHR_RIM_I8
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut SHR_RIM_I8 to " + i);
                        
                        i.setOp(Opcode.SHR_RIM_I8);
                        i.setEI8((ASMValue) i.getSource().location());
                        return true;
                    }
                }
                return false;
                
            case SAR_RIM:
                /*
                 * SAR_RIM_1    SAR reg, 1  1-2
                 * SAR_RIM_I8   SAR reg, i8 1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE) {
                    if(imm.value() == 1) {
                        // SAR_RIM_0
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut SAR_RIM_1 to " + i);
                        
                        i.setOp(Opcode.SAR_RIM_1);
                        return true;
                    } else if(signedWidth == 1) {
                        // SAR_RIM_I8
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut SAR_RIM_I8 to " + i);
                        
                        i.setOp(Opcode.SAR_RIM_I8);
                        i.setEI8((ASMValue) i.getSource().location());
                        return true;
                    }
                }
                return false;
                
            case ROL_RIM:
                /*
                 * ROL_RIM_1    ROL reg, 1  1-2
                 * ROL_RIM_I8   ROL reg, i8 1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE) {
                    if(imm.value() == 1) {
                        // ROL_RIM_0
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut ROL_RIM_1 to " + i);
                        
                        i.setOp(Opcode.ROL_RIM_1);
                        return true;
                    } else if(signedWidth == 1) {
                        // ROL_RIM_I8
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut ROL_RIM_I8 to " + i);
                        
                        i.setOp(Opcode.ROL_RIM_I8);
                        i.setEI8((ASMValue) i.getSource().location());
                        return true;
                    }
                }
                return false;
                
            case ROR_RIM:
                /*
                 * ROR_RIM_1    ROR reg, 1  1-2
                 * ROR_RIM_I8   ROR reg, i8 1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE) {
                    if(imm.value() == 1) {
                        // ROR_RIM_0
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut ROR_RIM_1 to " + i);
                        
                        i.setOp(Opcode.ROR_RIM_1);
                        return true;
                    } else if(signedWidth == 1) {
                        // ROR_RIM_I8
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut ROR_RIM_I8 to " + i);
                        
                        i.setOp(Opcode.ROR_RIM_I8);
                        i.setEI8((ASMValue) i.getSource().location());
                        return true;
                    }
                }
                return false;
                
            case RCL_RIM:
                /*
                 * RCL_RIM_1    RCL reg, 1  1-2
                 * RCL_RIM_I8   RCL reg, i8 1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE) {
                    if(imm.value() == 1) {
                        // RCL_RIM_0
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut RCL_RIM_1 to " + i);
                        
                        i.setOp(Opcode.RCL_RIM_1);
                        return true;
                    } else if(signedWidth == 1) {
                        // RCL_RIM_I8
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut RCL_RIM_I8 to " + i);
                        
                        i.setOp(Opcode.RCL_RIM_I8);
                        i.setEI8((ASMValue) i.getSource().location());
                        return true;
                    }
                }
                return false;
                
            case RCR_RIM:
                /*
                 * RCR_RIM_1    RCR reg, 1  1-2
                 * RCR_RIM_I8   RCR reg, i8 1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE) {
                    if(imm.value() == 1) {
                        // RCR_RIM_0
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut RCR_RIM_1 to " + i);
                        
                        i.setOp(Opcode.RCR_RIM_1);
                        return true;
                    } else if(signedWidth == 1) {
                        // RCR_RIM_I8
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut RCR_RIM_I8 to " + i);
                        
                        i.setOp(Opcode.RCR_RIM_I8);
                        i.setEI8((ASMValue) i.getSource().location());
                        return true;
                    }
                }
                return false;
                
            case CALL_RIM:
                /*
                 * CALL_I8      CALL i8     1
                 * CALL_I16     CALL i16    1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE) {
                    if(signedWidth == 1) {
                        // CALL_I8
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut CALL_I8 to " + i);
                        
                        i.setOp(Opcode.CALL_I8);
                        return true;
                    } else {
                        // CALL_I16
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut CALL_I16 to " + i);
                        
                        i.setOp(Opcode.CALL_I16);
                        return true;
                    }
                }
                return false;
                
            case CALLA_RIM32:
                /*
                 * CALLA_I32    CALLA i32   1
                 */
                if(sourceType == LocType.IMMEDIATE) {
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut CALLA_I32 to " + i);
                    
                    i.setOp(Opcode.CALLA_I32);
                    return true;
                }
                
            case JMP_RIM:
                /*
                 * JMP_I8      JMP i8     1
                 * JMP_I16     JMP i16    1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE) {
                    if(signedWidth == 1) {
                        // JMP_I8
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut JMP_I8 to " + i);
                        
                        i.setOp(Opcode.JMP_I8);
                        return true;
                    } else {
                        // JMP_I16
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut JMP_I16 to " + i);
                        
                        i.setOp(Opcode.JMP_I16);
                        return true;
                    }
                }
                return false;
                
            case INT_RIM:
                /*
                 * INT_I8   INT i8  1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && unsignedWidth == 1) {
                    // INT_I8
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut INT_I8 to " + i);
                    
                    i.setOp(Opcode.INT_I8);
                    return true;
                }
                return false;
                
            case JCC_RIM:
                /*
                 * JCC_I8   JCC i8  1
                 * Jcc_RIM  JCC rim 1
                 * Jcc_I8   JCC rim 2
                 */
                boolean isI8 = imm.resolved() && sourceType == LocType.IMMEDIATE && signedWidth == 1;
                Opcode newOp = isI8 ? Opcode.JCC_I8 : Opcode.INVALID;
                
                if(i.getEI8() instanceof ASMConstant c) {
                    newOp = switch((int) c.getValue()) {
                        case 0x00   -> isI8 ? Opcode.JMP_I8 : Opcode.JMP_RIM;
                        case 0x01   -> isI8 ? Opcode.JMP_I8 : Opcode.JMP_RIM;
                        case 0x02   -> isI8 ? Opcode.JC_I8 : Opcode.JC_RIM;
                        case 0x03   -> isI8 ? Opcode.JNC_I8 : Opcode.JNC_RIM;
                        case 0x04   -> isI8 ? Opcode.JS_I8 : Opcode.JS_RIM;
                        case 0x05   -> isI8 ? Opcode.JNS_I8 : Opcode.JNS_RIM;
                        case 0x06   -> isI8 ? Opcode.JO_I8 : Opcode.JO_RIM;
                        case 0x07   -> isI8 ? Opcode.JNO_I8 : Opcode.JNO_RIM;
                        case 0x08   -> isI8 ? Opcode.JZ_I8 : Opcode.JZ_RIM;
                        case 0x09   -> isI8 ? Opcode.JNZ_I8 : Opcode.JNZ_RIM;
                        case 0x0A   -> isI8 ? Opcode.JA_I8 : Opcode.JA_RIM;
                        case 0x0B   -> isI8 ? Opcode.JBE_I8 : Opcode.JBE_RIM;
                        case 0x0C   -> isI8 ? Opcode.JG_I8 : Opcode.JG_RIM;
                        case 0x0D   -> isI8 ? Opcode.JGE_I8 : Opcode.JGE_RIM;
                        case 0x0E   -> isI8 ? Opcode.JL_I8 : Opcode.JL_RIM;
                        case 0x0F   -> isI8 ? Opcode.JLE_I8 : Opcode.JLE_RIM;
                        default -> newOp;
                    };
                }
                
                if(newOp != Opcode.INVALID) {
                    // JCC_I8
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut " + newOp + " to " + i);
                    
                    i.setOp(newOp);
                    return true;
                }
                return false;
            
            case JCC_I8:
                /*
                 * JccI8    Jcc i8  1
                 */
                if(i.getEI8() instanceof ASMConstant c) {
                    newOp = switch((int) c.getValue()) {
                        case 0x00   -> Opcode.JMP_I8;
                        case 0x01   -> Opcode.JMP_I8;
                        case 0x02   -> Opcode.JC_I8; 
                        case 0x03   -> Opcode.JNC_I8;
                        case 0x04   -> Opcode.JS_I8; 
                        case 0x05   -> Opcode.JNS_I8;
                        case 0x06   -> Opcode.JO_I8; 
                        case 0x07   -> Opcode.JNO_I8;
                        case 0x08   -> Opcode.JZ_I8; 
                        case 0x09   -> Opcode.JNZ_I8;
                        case 0x0A   -> Opcode.JA_I8; 
                        case 0x0B   -> Opcode.JBE_I8;
                        case 0x0C   -> Opcode.JG_I8; 
                        case 0x0D   -> Opcode.JGE_I8;
                        case 0x0E   -> Opcode.JL_I8; 
                        case 0x0F   -> Opcode.JLE_I8;
                        default     -> Opcode.INVALID;
                    };
                    
                    if(newOp != Opcode.INVALID) {
                        // JCC_I8
                        if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut " + newOp + " to " + i);
                        
                        i.setOp(newOp);
                        return true;
                    }
                }
                
            case JC_RIM:
                /*
                 * JC_I8   JC i8  1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && signedWidth == 1) {
                    // JC_I8
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut JC_I8 to " + i);
                    
                    i.setOp(Opcode.JC_I8);
                    return true;
                }
                return false;
                
            case JNC_RIM:
                /*
                 * JNC_I8   JNC i8  1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && signedWidth == 1) {
                    // JNC_I8
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut JNC_I8 to " + i);
                    
                    i.setOp(Opcode.JNC_I8);
                    return true;
                }
                return false;
                
            case JS_RIM:
                /*
                 * JS_I8   JS i8  1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && signedWidth == 1) {
                    // JS_I8
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Applied shortcut JS_I8 to " + i);
                    
                    i.setOp(Opcode.JS_I8);
                    return true;
                }
                return false;
                
            case JNS_RIM:
                /*
                 * JNS_I8   JNS i8  1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && signedWidth == 1) {
                    // JNS_I8
                    LOG.finest("Applied shortcut JNS_I8 to " + i);
                    
                    i.setOp(Opcode.JNS_I8);
                    return true;
                }
                return false;
                
            case JO_RIM:
                /*
                 * JO_I8   JO i8  1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && signedWidth == 1) {
                    // JO_I8
                    LOG.finest("Applied shortcut JO_I8 to " + i);
                    
                    i.setOp(Opcode.JO_I8);
                    return true;
                }
                return false;
                
            case JNO_RIM:
                /*
                 * JNO_I8   JNO i8  1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && signedWidth == 1) {
                    // JNO_I8
                    LOG.finest("Applied shortcut JNO_I8 to " + i);
                    
                    i.setOp(Opcode.JNO_I8);
                    return true;
                }
                return false;
                
            case JZ_RIM:
                /*
                 * JZ_I8   JZ i8  1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && signedWidth == 1) {
                    // JZ_I8
                    LOG.finest("Applied shortcut JZ_I8 to " + i);
                    
                    i.setOp(Opcode.JZ_I8);
                    return true;
                }
                return false;
                
            case JNZ_RIM:
                /*
                 * JNZ_I8   JNZ i8  1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && signedWidth == 1) {
                    // JNZ_I8
                    LOG.finest("Applied shortcut JNZ_I8 to " + i);
                    
                    i.setOp(Opcode.JNZ_I8);
                    return true;
                }
                return false;
                
            case JA_RIM:
                /*
                 * JA_I8   JA i8  1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && signedWidth == 1) {
                    // JA_I8
                    LOG.finest("Applied shortcut JA_I8 to " + i);
                    
                    i.setOp(Opcode.JA_I8);
                    return true;
                }
                return false;
                
            case JBE_RIM:
                /*
                 * JBE_I8   JBE i8  1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && signedWidth == 1) {
                    // JBE_I8
                    LOG.finest("Applied shortcut JBE_I8 to " + i);
                    
                    i.setOp(Opcode.JBE_I8);
                    return true;
                }
                return false;
                
            case JG_RIM:
                /*
                 * JG_I8   JG i8  1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && signedWidth == 1) {
                    // JG_I8
                    LOG.finest("Applied shortcut JG_I8 to " + i);
                    
                    i.setOp(Opcode.JG_I8);
                    return true;
                }
                return false;
                
            case JGE_RIM:
                /*
                 * JGE_I8   JGE i8  1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && signedWidth == 1) {
                    // JGE_I8
                    LOG.finest("Applied shortcut JGE_I8 to " + i);
                    
                    i.setOp(Opcode.JGE_I8);
                    return true;
                }
                return false;
                
            case JL_RIM:
                /*
                 * JL_I8   JL i8  1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && signedWidth == 1) {
                    // JL_I8
                    LOG.finest("Applied shortcut JL_I8 to " + i);
                    
                    i.setOp(Opcode.JL_I8);
                    return true;
                }
                return false;
                
            case JLE_RIM:
                /*
                 * JLE_I8   JLE i8  1
                 */
                if(imm.resolved() && sourceType == LocType.IMMEDIATE && signedWidth == 1) {
                    // JLE_I8
                    LOG.finest("Applied shortcut JLE_I8 to " + i);
                    
                    i.setOp(Opcode.JLE_I8);
                    return true;
                }
                return false;
            
            default:
                // Nothing to do
                return false;
        }
    }
    
    /**
     * Attempt to shorten the immediate of an instruction
     * @param i
     * @param imm
     * @return
     */
    private static boolean tryShortenImmediate(ASMInstruction i, ResolvedImmediate imm) {
        if(LOG.isLoggable(Level.FINEST)) {
            //LOG.finest("Attempting to shorten immediate in " + i + " with immediate value " + imm);
        }
        
        /*
         * Given an immediate shortcut opcode, make it a shorter immediate shortcut opcode
         */
        
        int signedWidth = ASMUtil.getWidth(imm.value(), true, true, true, true);
        
        boolean isByte = imm.resolved() && signedWidth == 1,
                isWord = imm.resolved() && signedWidth <= 2;
        
        switch(i.getOp()) {
            case MOV_A_I16:
                // MOVS_A_I8    1
                if(isByte) {
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Shortened " + i + " to MOVS_A_I8");
                    
                    i.setOp(Opcode.MOVS_A_I8);
                    return true;
                }
                return false;
                
            case MOV_B_I16:
                // MOVS_B_I8    1
                if(isByte) {
                    LOG.finest("Shortened " + i + " to MOVS_B_I8");
                    
                    i.setOp(Opcode.MOVS_B_I8);
                    return true;
                }
                return false;
                
            case MOV_C_I16:
                // MOVS_C_I8    1
                if(isByte) {
                    LOG.finest("Shortened " + i + " to MOVS_C_I8");
                    
                    i.setOp(Opcode.MOVS_C_I8);
                    return true;
                }
                return false;
                
            case MOV_D_I16:
                // MOVS_D_I8    1
                if(isByte) {
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Shortened " + i + " to MOVS_D_I8");
                    
                    i.setOp(Opcode.MOVS_D_I8);
                    return true;
                }
                return false;
                
            case MOV_I_I16:
                // MOVS_I_I8    1
                if(isByte) {
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Shortened " + i + " to MOVS_I_I8");
                    
                    i.setOp(Opcode.MOVS_I_I8);
                    return true;
                }
                return false;
                
            case MOV_J_I16:
                // MOVS_J_I8    1
                if(isByte) {
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Shortened " + i + " to MOVS_J_I8");
                    
                    i.setOp(Opcode.MOVS_J_I8);
                    return true;
                }
                return false;
                
            case MOV_K_I16:
                // MOVS_K_I8    1
                if(isByte) {
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Shortened " + i + " to MOVS_K_I8");
                    
                    i.setOp(Opcode.MOVS_K_I8);
                    return true;
                }
                return false;
                
            case MOV_L_I16:
                // MOVS_L_I8    1
                if(isByte) {
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Shortened " + i + " to MOVS_L_I8");
                    
                    i.setOp(Opcode.MOVS_L_I8);
                    return true;
                }
                return false;
            
                /*
                 * TODO: Can we easily get the value of EI8? if so, apply _RIM_I8 -> _RIM_0/1
                 */
                
            case CALL_I32:
                // CALL_I8  3
                // CALL_I16 2
                if(isByte) {
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Shortened " + i + " to CALL_I8");
                    
                    i.setOp(Opcode.CALL_I8);
                    return true;
                } else if(isWord) {
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Shortened " + i + " to CALL_I16");
                    
                    i.setOp(Opcode.CALL_I16);
                    return true;
                }
                return false;
                
            case CALL_I16:
                // CALL_I8  1
                if(isByte) {
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Shortened " + i + " to CALL_I8");
                    
                    i.setOp(Opcode.CALL_I8);
                    return true;
                }
                return false;
                
            case JMP_I32:
                // JMP_I8   3
                // JMP_I16  2
                if(isByte) {
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Shortened " + i + " to JMP_I8");
                    
                    i.setOp(Opcode.JMP_I8);
                    return true;
                } else if(isWord) {
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Shortened " + i + " to JMP_I16");
                    
                    i.setOp(Opcode.JMP_I16);
                    return true;
                }
                return false;
                
            case JMP_I16:
                // JMP_I8  1
                if(isByte) {
                    if(LOG.isLoggable(Level.FINEST)) LOG.finest("Shortened " + i + " to JMP_I8");
                    
                    i.setOp(Opcode.JMP_I8);
                    return true;
                }
                return false;
                
            default:
                // Nothing to do
                return false;
        }
    }
    
}

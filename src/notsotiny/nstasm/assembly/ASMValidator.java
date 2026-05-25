package notsotiny.nstasm.assembly;

import java.util.Set;
import java.util.logging.Logger;

import notsotiny.nstasm.AssemblyOptions;
import notsotiny.nstasm.asmparts.ASMArgument;
import notsotiny.nstasm.asmparts.ASMComponent;
import notsotiny.nstasm.asmparts.ASMInstruction;
import notsotiny.nstasm.asmparts.ASMMemory;
import notsotiny.nstasm.asmparts.ASMObject;
import notsotiny.nstasm.asmparts.ASMReference;
import notsotiny.nstasm.asmparts.ASMRegister;
import notsotiny.nstasm.asmparts.ASMRepetition;
import notsotiny.nstasm.asmparts.ASMValue;
import notsotiny.nstasm.asmparts.ASMReference.ReferenceType;
import notsotiny.sim.Register;
import notsotiny.sim.ops.DecodingGroup;
import notsotiny.sim.ops.Opcode;

/**
 * A class which validates ASMObjects
 */
public class ASMValidator {
    
    private static Logger LOG = Logger.getLogger(ASMValidator.class.getName());
    
    /**
     * Validate the given object without accounting for immediate width.
     * If the given options allow it, instruction opcodes may be modified
     * Instructions will validate that immediates are within the allowed range during resolution.
     * @param obj
     * @param options
     * @return true if the object is valid
     */
    public static boolean validateUnresolved(ASMObject obj, Set<String> localLabelNames, AssemblyOptions options) {
        LOG.finer("Verifying object");
        
        // Process each component
        boolean valid = true;
        
        for(ASMComponent c : obj.getComponents()) {
            valid &= validateComponentUnresolved(c, localLabelNames, options);
        }
        
        return valid;
    }
    
    /**
     * Verify that a component is valid. Does not account for immediate width.
     * @param c
     * @param options
     * @return
     */
    private static boolean validateComponentUnresolved(ASMComponent c, Set<String> localLabelNames, AssemblyOptions options) {
        return switch(c) {
            case ASMInstruction i   -> validateInstructionUnresolved(i, localLabelNames, options);
            case ASMRepetition rep  -> {
                boolean valid = true;
                
                // Validate inner components
                for(ASMComponent ic : rep.getRepeatedComponents()) {
                    valid &= validateComponentUnresolved(ic, localLabelNames, options);
                }
                
                yield valid;
            }
            default -> true;
        };
    }
    
    /**
     * Verify that an instruction is valid. Does not account for immediate width.
     * @param i
     * @param options
     * @return
     */
    private static boolean validateInstructionUnresolved(ASMInstruction i, Set<String> localLabelNames, AssemblyOptions options) {
        tryFixJMPCALL(i, localLabelNames, options);
        tryFixFPR(i, options);
        
        DecodingGroup dg = i.getOp().dgroup;
        
        // Determine sizes
        int destSize = 0, sourceSize = 0;
        
        if(dg.hasDestination) {
            // Verify dest exists
            if(i.getDestination() == null) {
                LOG.severe("Missing destination in " + i);
                return false;
            }
            
            // Verify dest is not an immediate
            if(i.getDestination().location() instanceof ASMValue) {
                LOG.severe("Cannot use immediate as destination in " + i);
                return false;
            }
            
            // Get size
            destSize = getSize(i.getDestination());
        }
        
        if(dg.hasSource) {
            // Verify that source exists
            if(i.getSource() == null) {
                LOG.severe("Missing source in " + i);
                return false;
            }
            
            // Get size
            sourceSize = getSize(i.getSource());
        }
        
        // Verify only one memory/immediate
        if(dg.hasDestination && dg.hasSource) {
            boolean destIsM = i.getDestination().location() instanceof ASMMemory,
                    sourceIsI = i.getSource().location() instanceof ASMValue,
                    sourceIsM = i.getSource().location() instanceof ASMMemory;
            
            if(destIsM && (sourceIsI || sourceIsM)) {
                if(sourceIsI) {
                    // Might be able to do EI8
                    if(tryMakeEI8(i, options)) {
                        // Correction made. Try again
                        return validateInstructionUnresolved(i, localLabelNames, options);
                    }
                }
                
                LOG.severe("Cannot have " + (sourceIsI ? "both memory and immediate" : "multiple memory") + " in " + i);
                return false;
            }
        }
        
        // LEA does it's own thing
        if(dg == DecodingGroup.RIM_LEA) {
            // Destination must be r32
            if(destSize != 4 || !(i.getDestination().location() instanceof ASMRegister)) {
                LOG.severe("Destination of LEA must be a 32-bit register in " + i);
                return false;
            }
            
            // Source must be memory
            if(!(i.getSource().location() instanceof ASMMemory)) {
                LOG.severe("Source of LEA must be memory in " + i);
                return false;
            }
            
            return true;
        }
        
        // Verify sizes
        // Verify that destination is a valid size
        if(dg.hasDestination && destSize != 0 &&
           (dg.isPacked ? destSize != (dg.destIsWide ? 4 : 2) : (dg.destIsWide ? destSize < 2 : destSize > 2))) {
            // Can we correct it?
            if(!dg.isPacked && !dg.destIsWide && destSize > 2) {
                // Destination arg is wide but operation destination is not. Is there a wide variant?
                if(tryMakeWide(i, options)) {
                    // Correction made. Try again
                    return validateInstructionUnresolved(i, localLabelNames, options);
                }
            }
            
            LOG.severe("Invalid size " + destSize + " for destination in " + i);
            return false;
        }
        
        // Verify that source is a valid size
        if(dg.hasSource && sourceSize != 0 &&
           (dg.isPacked ? sourceSize != (dg.sourceIsWide ? 4 : 2) : (dg.sourceIsWide ? sourceSize < 2 : sourceSize > 2))) {
            // Can we correct it?
            if(!dg.isPacked && !dg.sourceIsWide && sourceSize > 2) {
                // Source arg is wide but operation source is not. Is there a wide variant?
                if(tryMakeWide(i, options)) {
                    // Correction made. Try again
                    return validateInstructionUnresolved(i, localLabelNames, options);
                }
            } else if(!dg.isPacked && sourceSize == 1 && i.getSource().location() instanceof ASMValue) {
                // Source is a byte but that's invalid. Is there an EI8 variant?
                if(tryMakeEI8(i, options)) {
                    // Correction made. Try again
                    return validateInstructionUnresolved(i, localLabelNames, options);
                }
            }
            
            LOG.severe("Invalid size " + sourceSize + " for source in " + i);
        }
        
        // Verify that source and destination have the right relations
        // R32 source/dest don't care about relations
        boolean hasR32 = switch(dg) {
            case RIM_R32S_WOD, RIM_WIDE_R32S_WOD, RIM_R32D, RIM_WIDE_R32D -> true;
            default -> false;
        };
        
        if(!hasR32 && dg.hasSource && dg.hasDestination && destSize != 0 && sourceSize != 0 && !dg.isPacked) {
            if(dg.destIsWide) {
                if(dg.sourceIsWide) {
                    // Must match
                    if(destSize != sourceSize) {
                        // EI8 source?
                        if(sourceSize == 1 && i.getSource().location() instanceof ASMValue) {
                            // Source is a byte but that's invalid. Is there an EI8 variant?
                            if(tryMakeEI8(i, options)) {
                                // Correction made. Try again
                                return validateInstructionUnresolved(i, localLabelNames, options);
                            }
                        }
                        
                        LOG.severe("Invalid combination of sizes " + destSize + " and " + sourceSize + " in " + i);
                        return false;
                    }
                } else {
                    // source = dest / 2
                    if(sourceSize != destSize / 2) {
                        if(sourceSize == 1 && i.getSource().location() instanceof ASMValue) {
                            // Source is a byte but that's invalid. Is there an EI8 variant?
                            if(tryMakeEI8(i, options)) {
                                // Correction made. Try again
                                return validateInstructionUnresolved(i, localLabelNames, options);
                            }
                        }
                        
                        LOG.severe("Invalid combination of sizes " + destSize + " and " + sourceSize + " in " + i);
                        return false;
                    }
                }
            } else {
                // normal dest wide source does not exist
                // Must match
                if(destSize != sourceSize) {
                    if(sourceSize == 1 && i.getSource().location() instanceof ASMValue) {
                        // Source is a byte but that's invalid. Is there an EI8 variant?
                        if(tryMakeEI8(i, options)) {
                            // Correction made. Try again
                            return validateInstructionUnresolved(i, localLabelNames, options);
                        }
                    }
                    
                    LOG.severe("Invalid combination of sizes " + destSize + " and " + sourceSize + " in " + i);
                    return false;
                }
            }
        }
        
        // Verify EI8
        if(dg.hasEI8 && i.getEI8() == null) {
            LOG.severe("Missing EI8 in " + i);
            return false;
        }
        
        // Verify other specifications
        switch(dg) {
                
            case RIM_R32D, RIM_WIDE_R32D:
                // 32-bit register destination
                if(destSize != 4 || !(i.getDestination().location() instanceof ASMRegister)) {
                    LOG.severe("Destination must be a 32-bit register in " + i);
                    return false;
                }
                break;
                
            case RIM_R32S_WOD, RIM_WIDE_R32S_WOD:
                // 32-bit register source
                if(sourceSize != 4 || !(i.getSource().location() instanceof ASMRegister)) {
                    LOG.severe("Source must be a 32-bit register in " + i);
                    return false;
                }
                break;
            
            default:
        }
        
        return true;
    }
    
    /**
     * Special opcodes are required for working with F and protected registers
     * @param i
     * @param options
     * @return
     */
    private static boolean tryFixFPR(ASMInstruction i, AssemblyOptions options) {
        if(options.allowMnemonicCorrection()) {
            // are we dealing with F or protected registers?
            boolean destIsF = false,
                    destIsPR = false,
                    sourceIsF = false,
                    sourceIsPR = false;
            
            Register sourceReg = Register.NONE,
                     destReg = Register.NONE;
            
            if(i.getSource() != null && i.getSource().location() instanceof ASMRegister ar) {
                sourceIsF = ar.reg() == Register.F;
                sourceIsPR = switch(ar.reg()) {
                    case PF, ISP    -> true;
                    default         -> false;
                };
                sourceReg = ar.reg();
            }
            
            if(i.getDestination() != null && i.getDestination().location() instanceof ASMRegister ar) {
                destIsF = ar.reg() == Register.F;
                destIsPR = switch(ar.reg()) {
                    case PF, ISP    -> true;
                    default         -> false;
                };
                destReg = ar.reg();
            }
            
            Opcode newOp = Opcode.INVALID;
            
            if(sourceIsF || destIsF) {
                // We're dealing with F
                if(sourceIsF) {
                    newOp = switch(i.getOp()) {
                        case MOV_RIM    -> Opcode.MOV_RIM_F;
                        case DST_RIM    -> destReg == Register.SP ? Opcode.PUSH_F : Opcode.INVALID;
                        case AND_RIM    -> Opcode.AND_RIM_F;
                        case OR_RIM     -> Opcode.OR_RIM_F;
                        case XOR_RIM    -> Opcode.XOR_RIM_F;
                        default         -> Opcode.INVALID;
                    };
                } else {
                    newOp = switch(i.getOp()) {
                        case MOV_RIM    -> Opcode.MOV_F_RIM;
                        case LDI_RIM    -> sourceReg == Register.SP ? Opcode.POP_F : Opcode.INVALID;
                        case AND_RIM    -> Opcode.AND_F_RIM;
                        case OR_RIM     -> Opcode.OR_F_RIM;
                        case XOR_RIM    -> Opcode.XOR_F_RIM;
                        default         -> Opcode.INVALID;
                    };
                }
            } else if(sourceIsPR || destIsPR) {
                // We're dealing with a protected register
                if(i.getOp() == Opcode.MOV_RIM) {
                    // Correct it
                    newOp = sourceIsPR ? Opcode.MOV_RIM_PR : Opcode.MOV_PR_RIM;
                } else if(i.getOp() == Opcode.DST_RIM && destReg == Register.SP && sourceReg == Register.PF) {
                    newOp = Opcode.PUSH_PF;
                } else if(i.getOp() == Opcode.LDI_RIM && destReg == Register.PF && sourceReg == Register.SP) {
                    newOp = Opcode.POP_PF;
                }

                // Other operations can't do protected registers
            }
            
            if(newOp != Opcode.INVALID) {
                LOG.finest("Corrected opcode in " + i + " to " + newOp);
                i.setOp(newOp);
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * For JMP/CALL, infer _RIM with non-local reference as absolute and _RIM with local reference as _I32
     * @param i
     * @param options
     * @return
     */
    private static boolean tryFixJMPCALL(ASMInstruction i, Set<String> localLabelNames, AssemblyOptions options) {
        if(options.allowMnemonicCorrection()) {
            boolean isJMP = i.getOp() == Opcode.JMP_RIM,
                    isCALL = i.getOp() == Opcode.CALL_RIM;
            
            if(isJMP || isCALL) {
                // NORMAL is expected from other sources, RELATIVE_CURRENT is inferred by parser
                if((i.getSource().size() == 4 || i.getSource().size() == 0) &&  i.getSource().location() instanceof ASMReference ref) {
                    Opcode newOp = Opcode.INVALID;
                    
                    boolean normalOrLikelyInferred = ref.getType() == ReferenceType.RELATIVE_CURRENT || ref.getType() == ReferenceType.NORMAL; 
                    
                    if(normalOrLikelyInferred && localLabelNames.contains(ref.getName())) {
                        // Local
                        newOp = isJMP ? Opcode.JMP_I32 : Opcode.CALL_I32;
                    } else if(normalOrLikelyInferred && !ref.getName().equals("")){
                        // Non-local
                        newOp = isJMP ? Opcode.JMPA_I32 : Opcode.CALLA_I32;
                        i.setSource(new ASMArgument(new ASMReference(ref.getName(), ReferenceType.NORMAL), i.getSource().size()));
                    }
                    
                    if(newOp != Opcode.INVALID) {
                        LOG.finest("Corrected opcode in " + i + " to " + newOp);
                        i.setOp(newOp);
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
    
    /**
     * Attempt to correct the opcode of an instruction if its source can be an EI8
     * @param i
     * @param options
     * @return
     */
    private static boolean tryMakeEI8(ASMInstruction i, AssemblyOptions options) {
        if(options.allowMnemonicCorrection()) {
            Opcode newOp = switch(i.getOp()) {
                case CMP_RIM    -> Opcode.CMP_RIM_I8;
                case CMPW_RIM   -> Opcode.CMPW_RIM_I8;
                case ADD_RIM    -> Opcode.ADD_RIM_I8;
                case ADDW_RIM   -> Opcode.ADDW_RIM_I8;
                case ADC_RIM    -> Opcode.ADC_RIM_I8;
                case ADCW_RIM   -> Opcode.ADCW_RIM_I8;
                case SUB_RIM    -> Opcode.SUB_RIM_I8;
                case SUBW_RIM   -> Opcode.SUBW_RIM_I8;
                case SBB_RIM    -> Opcode.SBB_RIM_I8;
                case SBBW_RIM   -> Opcode.SBBW_RIM_I8;
                case SHL_RIM    -> Opcode.SHL_RIM_I8;
                case SHR_RIM    -> Opcode.SHR_RIM_I8;
                case SAR_RIM    -> Opcode.SAR_RIM_I8;
                case ROL_RIM    -> Opcode.ROL_RIM_I8;
                case ROR_RIM    -> Opcode.ROR_RIM_I8;
                case RCL_RIM    -> Opcode.RCL_RIM_I8;
                case RCR_RIM    -> Opcode.RCR_RIM_I8;
                default         -> Opcode.INVALID;
            };
            
            if(newOp == Opcode.INVALID) {
                return false;
            }
            
            LOG.finest("Corrected opcode in " + i + " to " + newOp);
            i.setOp(newOp);
            i.setEI8((ASMValue) i.getSource().location());
            return true;
        } else {
            return false;
        }
    }
    
    /**
     * Attempt to correct the opcode of an instruction if its arguments can be wide
     * @param i
     * @param options
     * @return true if opcode was changed
     */
    private static boolean tryMakeWide(ASMInstruction i, AssemblyOptions options) {
        if(options.allowMnemonicCorrection()) {
            Opcode newOp = switch(i.getOp()) {
                case MOV_RIM    -> Opcode.MOVW_RIM;
                case XCHG_RIM   -> Opcode.XCHGW_RIM;
                case CMOVCC_RIM -> Opcode.CMOVWCC_RIM;
                case MOV_RIM_BP -> Opcode.MOVW_RIM_BP;
                case MOV_BP_RIM -> Opcode.MOVW_RIM_BP;
                case LDI_RIM    -> Opcode.LDIW_RIM;
                case DLD_RIM    -> Opcode.DLDW_RIM;
                case STI_RIM    -> Opcode.STIW_RIM;
                case DST_RIM    -> Opcode.DSTW_RIM;
                case CMP_RIM_0  -> Opcode.CMPW_RIM_0;
                case CMP_RIM_I8 -> Opcode.CMPW_RIM_I8;
                case CMP_RIM    -> Opcode.CMPW_RIM;
                case ADD_RIM_I8 -> Opcode.ADDW_RIM_I8;
                case ADD_RIM    -> Opcode.ADDW_RIM;
                case ADC_RIM_I8 -> Opcode.ADCW_RIM_I8;
                case ADC_RIM    -> Opcode.ADCW_RIM;
                case SUB_RIM_I8 -> Opcode.SUBW_RIM_I8;
                case SUB_RIM    -> Opcode.SUBW_RIM;
                case SBB_RIM_I8 -> Opcode.SBBW_RIM_I8;
                case SBB_RIM    -> Opcode.SBBW_RIM;
                case INC_RIM    -> Opcode.INCW_RIM;
                case ICC_RIM    -> Opcode.ICCW_RIM;
                case DEC_RIM    -> Opcode.DECW_RIM;
                case DCC_RIM    -> Opcode.DCCW_RIM;
                case NEG_RIM    -> Opcode.NEGW_RIM;
                default         -> Opcode.INVALID;
            };
            
            if(newOp == Opcode.INVALID) {
                return false;
            }
            
            LOG.finest("Corrected opcode in " + i + " to " + newOp);
            i.setOp(newOp);
            return true;
        } else {
            return false;
        }
    }
    
    /**
     * Get an argument's size
     * @param arg
     * @return
     */
    private static int getSize(ASMArgument arg) {
        return arg.size() != 0 ? arg.size() : switch(arg.location()) {
            case ASMRegister ar -> ar.reg().size();
            default -> 0;
        };
    }
}

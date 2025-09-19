package notsotiny.nstasm.asmparts;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import notsotiny.nstasm.ASMUtil;
import notsotiny.nstasm.asmparts.ASMReference.ReferenceType;
import notsotiny.nstasm.assembly.ASMResolver;
import notsotiny.nstasm.assembly.UnresolvableException;
import notsotiny.sim.Register;
import notsotiny.sim.ops.DecodingGroup;
import notsotiny.sim.ops.Opcode;

/**
 * An assembly instruction
 */
public class ASMInstruction implements ASMComponent {
    
    private static Logger LOG = Logger.getLogger(ASMInstruction.class.getName());
    
    /**
     * Packing type
     */
    public enum ASMPacking {
        NONE, FOURS, EIGHTS
    }
    
    private Opcode op;
    
    private ASMArgument destination, source;
    
    private ASMValue ei8;
    
    private ASMPacking packType;
    
    /**
     * Full constructor
     * @param op
     * @param destination
     * @param source
     * @param ei8
     * @param packType
     */
    public ASMInstruction(Opcode op, ASMArgument destination, ASMArgument source, ASMValue ei8, ASMPacking packType) {
        this.op = op;
        this.destination = destination;
        this.source = source;
        this.ei8 = ei8;
        this.packType = packType;
    }
    
    /**
     * No-packing full constructor
     * @param op
     * @param destination
     * @param source
     * @param ei8
     */
    public ASMInstruction(Opcode op, ASMArgument destination, ASMArgument source, ASMValue ei8) {
        this(op, destination, source, ei8, ASMPacking.NONE);
    }
    
    /**
     * Two-argument constructor
     * @param op
     * @param destination
     * @param source
     */
    public ASMInstruction(Opcode op, ASMArgument destination, ASMArgument source, ASMPacking packType) {
        this(op, destination, source, null, packType);
    }
    
    /**
     * No-packing Two-argument constructor
     * @param op
     * @param destination
     * @param source
     */
    public ASMInstruction(Opcode op, ASMArgument destination, ASMArgument source) {
        this(op, destination, source, null, ASMPacking.NONE);
    }
    
    /**
     * One-argument ei8 constructor
     * @param op
     * @param argument
     * @param packType
     */
    public ASMInstruction(Opcode op, ASMArgument argument, ASMValue ei8Value, ASMPacking packType) {
        this(op, null, null, ei8Value, packType);
        
        if(op.dgroup.hasSource && !op.dgroup.hasDestination) {
            // source only
            this.source = argument;
        } else if(!op.dgroup.hasSource && op.dgroup.hasDestination) {
            // dest only
            this.destination = argument;
        } else {
            // neither
            throw new IllegalArgumentException(op + " is not single-argument");
        }
    }
    
    /**
     * One-argument constructor
     * @param op
     * @param argument
     * @param packType
     */
    public ASMInstruction(Opcode op, ASMArgument argument, ASMPacking packType) {
        this(op, argument, (ASMValue) null, packType);
    }
    
    /**
     * No-packing one-argument ei8 constructor
     * @param op
     * @param argument
     * @param ei8Value
     */
    public ASMInstruction(Opcode op, ASMArgument argument, ASMValue ei8Value) {
        this(op, argument, ei8Value, ASMPacking.NONE);
    }
    
    /**
     * No-packing one-argument constructor
     * @param op
     * @param argument
     */
    public ASMInstruction(Opcode op, ASMArgument argument) {
        this(op, argument, ASMPacking.NONE);
    }
    
    /**
     * No-argument constructor
     * @param op
     */
    public ASMInstruction(Opcode op) {
        this(op, null, null, null, ASMPacking.NONE);
    }
    
    public void setOp(Opcode newOp) { this.op = newOp; }
    public void setSource(ASMArgument arg) { this.source = arg; }
    public void setDestination(ASMArgument arg) { this.destination = arg; }
    public void setEI8(ASMValue v) { this.ei8 = v; }
    public void setPacking(ASMPacking p) { this.packType = p; }
    
    public Opcode getOp() { return this.op; }
    public ASMArgument getDestination() { return this.destination; }
    public ASMArgument getSource() { return this.source; }
    public ASMValue getEI8() { return this.ei8; }
    public ASMPacking getPacking() { return this.packType; }
    
    @Override
    public int getMaxSize() {
        // Lazy way
        return switch(this.op.dgroup) {
            case NODECODE       -> 1;   // op
            case I8             -> 2;   // op imm
            case I8_EI8, I16    -> 3;   // op 2ximm
            case I32            -> 5;   // op 4ximm
            case RIM_WOD_EI8, RIM_PACKED_EI8, RIM_WIDE_WOD_EI8, RIM_SO_EI8, RIM_WIDE_DO_EI8, RIM_DO_EI8,
                 RIM_RS_SO_EI8, RIM_WIDE_RS_SO_EI8, RIM_RD_DO_WOD_EI8, RIM_WIDE_RD_DO_WOD_EI8 // a couple of these are smaller i think but w/e
                    -> 8;   // op tim bio 5ximm
            default -> 7;   // op rim bio 4ximm 
        };
    }
    
    @Override
    public List<Byte> getBytes(ASMResolver resolver) throws UnresolvableException {
        List<Byte> bytes = new ArrayList<>(8);
        
        DecodingGroup dgroup = this.op.dgroup;
        
        // Opcode
        bytes.add(this.op.op);
        
        // RIM, BIO
        if(dgroup.hasRIM) {
            byte rim = 0;
            
            // useful conditions
            boolean sourceIsRegister = dgroup.hasSource ? this.source.location() instanceof ASMRegister : false,
                    destIsRegister = dgroup.hasDestination ? this.destination.location() instanceof ASMRegister : false,
                    sourceIsMemory = dgroup.hasSource ? this.source.location() instanceof ASMMemory : false,
                    destIsMemory = dgroup.hasDestination ? this.destination.location() instanceof ASMMemory : false,
                    sourceIsImmediate = dgroup.hasSource ? this.source.location() instanceof ASMValue : false;
            
            // Operand sizes
            int sourceSize =  (!dgroup.hasSource || this.source == null) ? 0 : (sourceIsImmediate ? this.source.size() : realSize(this.source, resolver));
            int immediateSize = (!dgroup.hasSource || this.source == null || !sourceIsImmediate) ? 0 : realSize(this.source, resolver); 
            int destSize = (!dgroup.hasDestination || this.destination == null) ? 0 : realSize(this.destination, resolver);
            
            boolean sourceSizeInferred = dgroup.hasSource ? this.source.size() == 0 : false;
            
            // Branches allow immeidate to determine size
            if(sourceSize == 0 && sourceIsImmediate) {
                switch(this.op) {
                    case JMP_I8, JMP_I16, JMP_I32, JMP_RIM, JCC_I8, JCC_RIM,
                         JC_I8, JNC_I8, JS_I8, JNS_I8, JO_I8, JNO_I8, JZ_I8, JNZ_I8,
                         JA_I8, JBE_I8, JG_I8, JGE_I8, JL_I8, JLE_I8,
                         JC_RIM, JNC_RIM, JS_RIM, JNS_RIM, JO_RIM, JNO_RIM, JZ_RIM, JNZ_RIM,
                         JA_RIM, JBE_RIM, JG_RIM, JGE_RIM, JL_RIM, JLE_RIM,
                         CALL_I8, CALL_I16, CALL_I32, CALL_RIM, INT_RIM:
                        sourceSize = immediateSize;
                        break;
                    
                    default:
                        // Not allowed
                }
            }
            
            // Infer sizes if not known
            if(sourceSize <= 0 && destSize <= 0) {
                // Inferring both for rim is only valid in special cases
                switch(this.op) {
                    case JMPA_RIM32, CALLA_RIM32:
                        sourceSize = 4;
                        break;
                    
                    default:
                        LOG.severe("Cannot infer operand sizes for " + this);
                        throw new UnresolvableException();
                }
            }
            
            if(dgroup.hasSource && sourceSize <= 0) {
                // Source isn't known, but dest is. Copy or halve?
                if(dgroup.destIsWide && !dgroup.sourceIsWide) {
                    // Validate dest size
                    if(destSize == 1) {
                        LOG.severe("Invalid destination for wide inference in " + this);
                        throw new UnresolvableException();
                    }
                    
                    // halve
                    sourceSize = destSize / 2;
                } else {
                    // copy
                    sourceSize = destSize;
                }
            } else if(dgroup.hasDestination && destSize <= 0) {
                // Source is known, dest isn't. Copy or double?
                if(dgroup.destIsWide && !dgroup.sourceIsWide) {
                    // validate
                    if(sourceSize == 4) {
                        LOG.severe("Invalid source for wide inference in " + this);
                        throw new UnresolvableException();
                    }
                    
                    // double
                    destSize = sourceSize * 2;
                } else {
                    // copy
                    destSize = sourceSize;
                }
            }
            
            // Validate sizes
            // Immediates cannot exceed their size
            if(immediateSize > sourceSize) {
                LOG.severe("Immediate too large for " + this);
                throw new UnresolvableException();
            }
            
            // Validate sizes' relations are correct
            switch(dgroup) {
                case RIM_NORMAL, RIM_WOD, RIM_WOD_EI8:
                    // Sizes must be equal & normal
                    if(sourceSize != destSize) {
                        // Correct immediate if applicable
                        if(sourceSize < destSize && sourceIsImmediate) {
                            // Corrected immediate
                            sourceSize = destSize;
                        } else {
                            // Invalid
                            LOG.severe("Operand sizes " + destSize + " and " + sourceSize + " do not match in " + this);
                            throw new UnresolvableException();
                        }
                    } else if(sourceSize > 2 || destSize > 2) {
                        LOG.severe("Invalid operand size(s) in " + this);
                        throw new UnresolvableException();
                    }
                    break;
                
                case RIM_PACKED, RIM_PACKED_EI8:
                    // both must be 2
                    if(sourceSize < 2 && sourceIsImmediate) {
                        sourceSize = 2;
                    } else if(sourceSize != 2 || destSize != 2) {
                        LOG.severe("Invalid operand size(s) in " + this);
                        throw new UnresolvableException();
                    }
                    break;
                    
                case RIM_WIDEDST, RIM_WIDEDST_WOD:
                    // Destination must be 2x source (handles needing wide)
                    if(destSize / sourceSize != 2) {
                        // Correct immediate if applicable
                        if(sourceSize < (destSize / 2) && sourceIsImmediate) {
                            sourceSize = destSize / 2;
                        } else {
                            // Invalid
                            LOG.severe("Operand sizes do not match in " + this);
                            throw new UnresolvableException();
                        }
                    }
                    break;
                    
                case RIM_PACKED_WIDEDST:
                    // dest must be 4, source must be 2
                    if(sourceSize < 2 && sourceIsImmediate) {
                        sourceSize = 2;
                    } else if(sourceSize != 2 || destSize != 4) {
                        LOG.severe("Invalid operand size(s) in " + this);
                        throw new UnresolvableException();
                    }
                    break;
                
                case RIM_WIDE, RIM_WIDE_WOD, RIM_WIDE_WOD_EI8:
                    // Sizes must be equal & wide
                    if(sourceSize != destSize) {
                        // Correct immediate if applicable
                        if(sourceSize < destSize && sourceIsImmediate) {
                            // Corrected immediate
                            sourceSize = destSize;
                        } else {
                            // Invalid
                            LOG.severe("Operand sizes do not match in " + this);
                            throw new UnresolvableException();
                        }
                    } else if((sourceSize < 2 && (!sourceSizeInferred || !sourceIsImmediate)) || destSize < 2) {
                        LOG.severe("Invalid operand size(s) in " + this);
                        throw new UnresolvableException();
                    }
                    break;
                    
                case RIM_DO, RIM_DO_EI8, RIM_DO_WOD, RIM_R32S_WOD:
                    // Destination must be normal
                    if(destSize > 2) {
                        LOG.severe("Invalid destination size " + destSize + " in " + this);
                        throw new UnresolvableException();
                    }
                    break;
                
                case RIM_WIDE_DO_WOD, RIM_WIDE_DO, RIM_WIDE_DO_EI8:
                    // Destination must be wide
                    if(destSize < 2) {
                        LOG.severe("Invalid destination size " + destSize + " in " + this);
                        throw new UnresolvableException();
                    }
                    break;
                    
                case RIM_PACKED_DO:
                    // Destination must be 2
                    if(destSize != 2) {
                        LOG.severe("Invalid destination size " + destSize + " in " + this);
                        throw new UnresolvableException();
                    }
                    break;
                    
                case RIM_SO, RIM_SO_EI8:
                    // Source must be normal
                    if(sourceSize > 2) {
                        LOG.severe("Invalid source size " + sourceSize + " in " + this);
                        throw new UnresolvableException();
                    }
                    break;
                    
                case RIM_WIDE_SO, RIM_WIDE_R32D:
                    // Source must be wide
                    if(sourceSize < 2 && (!sourceSizeInferred || !sourceIsImmediate)) {
                        LOG.severe("Invalid source size " + sourceSize + " in " + this);
                        throw new UnresolvableException();
                    }
                    break;
                
                default:
                    // no validation needed
                    // register-source, register-destination opcodes are validated elsewhere
            }
            
            // 's' bit
            if(dgroup.isPacked) {
                // Packed is from packing type
                rim |= this.packType == ASMPacking.FOURS ? 0b10_000_000 : 0;
            } else {
                // From operand sizes
                if((sourceSize == 1 && this.op != Opcode.LEA_RIM) || destSize == 1 || (dgroup.sourceIsWide && sourceSize == 2)) {
                    rim |= 0b10_000_000;
                }
            }
            
            // 'r' bit
            // 0 = register-register (not present = register)
            
            if(!((sourceIsRegister || !dgroup.hasSource) && (destIsRegister || !dgroup.hasDestination))) {
                rim |= 0b01_000_000;
            }
            
            // 'reg' field
            // reg is destination register unless destination is memory
            if(dgroup.hasDestination) {
                Register r;
                String rs;
                
                if(destIsRegister) {
                    // reg is destination
                    r = ((ASMRegister) this.destination.location()).reg();
                    rs = "destination";
                } else {
                    // reg is source
                    r = dgroup.hasSource ? ((ASMRegister) this.source.location()).reg() : Register.NONE;
                    rs = "source";
                }
                
                rim |= switch(r) {
                    case AL, A, DA  -> 0b00_000_000;
                    case BL, B, BC  -> 0b00_001_000;
                    case CL, C, JI  -> 0b00_010_000;
                    case DL, D, LK  -> 0b00_011_000;
                    case AH, I, XP  -> 0b00_100_000;
                    case BH, J, YP  -> 0b00_101_000;
                    case CH, K, BP  -> 0b00_110_000;
                    case DH, L, SP  -> 0b00_111_000;
                    case PF, ISP    -> 0b00_000_000;
                    case F, NONE    -> 0b00_000_000;
                    default -> throw new IllegalArgumentException("Invalid " + rs + " register in " + this);
                };
            }
            
            // 'rim' field
            if(sourceIsRegister && (destIsRegister || !dgroup.hasDestination)) {
                // register-register
                rim |= switch(((ASMRegister) this.source.location()).reg()) {
                    case AL, A, DA  -> 0b00_000_000;
                    case BL, B, BC  -> 0b00_000_001;
                    case CL, C, JI  -> 0b00_000_010;
                    case DL, D, LK  -> 0b00_000_011;
                    case AH, I, XP  -> 0b00_000_100;
                    case BH, J, YP  -> 0b00_000_101;
                    case CH, K, BP  -> 0b00_000_110;
                    case DH, L, SP  -> 0b00_000_111;
                    case PF, ISP    -> 0b00_000_000;
                    case F, NONE    -> 0b00_000_000;
                    default -> throw new IllegalArgumentException("Invalid source register in " + this);
                };
                
                bytes.add(rim);
            } else if((dgroup.hasSource && sourceIsMemory) || (dgroup.hasDestination && destIsMemory)) {
                // memory exists
                ASMMemory mem = (ASMMemory)(sourceIsMemory ? this.source.location() : this.destination.location());
                
                // do we include offset
                long offset = 0;
                int offsetSize = 0;
                boolean resolved = true;
                
                if(mem.getOffset() instanceof ASMReference ref && ref.getType() == ReferenceType.NORMAL) {
                    // Unresolved address. must include
                    offsetSize = 4;
                    resolved = false;
                } else if(mem.getOffset() != null) {
                    // Offset exists
                    offset = expectResolvedValue(mem.getOffset(), resolver);
                    offsetSize = offset == 0 ? 0 : ASMUtil.getWidth(offset, true, true, true, true);
                } else {
                    // Offset does not exist
                    offsetSize = 0;
                }
                
                // do we include bio byte
                if(mem.getBase() != Register.NONE || mem.getIndex() != Register.NONE) {
                    // yes, include bio byte
                    rim |= (offsetSize != 0) ? (sourceIsMemory ? 0b00_000_011 : 0b00_000_111) : (sourceIsMemory ? 0b00_000_010 : 0b00_000_110);
                    
                    // make bio byte
                    byte bio = 0;
                    
                    boolean ipRelative = mem.getBase() == Register.IP;
                    boolean hasIndex = mem.getIndex() != Register.NONE;
                    
                    int scale = hasIndex ? 1 : 0;
                    
                    // Handle scale since it needs resolving
                    if(hasIndex && mem.getScale() != null) {
                        scale = (int) expectResolvedValue(mem.getScale(), resolver);
                        
                        if(!(scale == 1 || scale == 2 || scale == 4)) {
                            LOG.severe("Invalid scale " + scale + " in " + this);
                            throw new UnresolvableException();
                        }
                    }
                    
                    // handle base/index
                    if(!ipRelative) {
                        // Normal
                        // Base
                        bio |= switch(mem.getBase()) {
                            case DA     -> 0b00_000_000;
                            case BC     -> 0b00_001_000;
                            case JI     -> 0b00_010_000;
                            case LK     -> 0b00_011_000;
                            case XP     -> 0b00_100_000;
                            case YP     -> 0b00_101_000;
                            case BP     -> 0b00_110_000;
                            case SP     -> 0b00_111_000;
                            case NONE   -> 0b00_111_000;
                            default -> {
                                LOG.severe("Invalid base " + mem.getBase() + " in " + this);
                                throw new UnresolvableException();
                            }
                        };
                        
                        // Index
                        bio |= switch(mem.getIndex()) {
                            case A      -> 0b00_000_000;
                            case B      -> 0b00_000_001;
                            case C      -> 0b00_000_010;
                            case D      -> 0b00_000_011;
                            case I      -> 0b00_000_100;
                            case J      -> 0b00_000_101;
                            case K      -> 0b00_000_110;
                            case L      -> 0b00_000_111;
                            case NONE   -> 0b00_000_000;
                            default -> {
                                LOG.severe("Invalid index " + mem.getIndex() + " in " + this);
                                throw new UnresolvableException();
                            }
                        };
                        
                        // Scale
                        if(hasIndex) {
                            offsetSize = (offsetSize != 0) ? 4 : 0;
                            bio |= switch(scale) {
                                case 1  -> 0b01_000_000;
                                case 2  -> 0b10_000_000;
                                case 4  -> 0b11_000_000;
                                default -> throw new IllegalStateException("Unreachable"); // handled previously
                            };
                        }
                    } else {
                        // IP-relative
                        // Index
                        bio |= switch(mem.getIndex()) {
                            case I      -> 0b00_100_100;
                            case J      -> 0b00_101_100;
                            case K      -> 0b00_110_100;
                            case L      -> 0b00_111_100;
                            case NONE   -> 0b00_000_100;
                            default -> {
                                LOG.severe("Invalid index " + mem.getIndex() + " in " + this);
                                throw new UnresolvableException();
                            }
                        };
                        
                        // IP relative can't do scale
                        if(scale > 1) {
                            LOG.severe("Invalid IP-relative scale " + scale + " in " + this);
                            throw new UnresolvableException();
                        }
                    }
                    
                    // Offset size if applicable
                    if(scale == 0 && offsetSize != 0) {
                        bio |= (offsetSize - 1) & 0b0011;
                    }
                    
                    bytes.add(rim);
                    bytes.add(bio);
                } else {
                    // no, offset only
                    rim |= sourceIsMemory ? 0b00_000_001 : 0b00_000_101;
                    offsetSize = 4;
                    bytes.add(rim);
                }
                
                if(offsetSize != 0) {
                    if(!resolved) {
                        // Place reference for unresolved offset
                        resolver.placeReference(((ASMReference) mem.getOffset()).getName(), bytes.size());
                    } else {
                        resolver.setImmediate(offset);
                    }
                    
                    ASMUtil.addBytes(bytes, offset, offsetSize);
                } else {
                    resolver.setImmediate(0);
                }
            } else if(dgroup.hasSource) {
                // source exists and is neither register nor memory, must be immediate
                // Add rim before immediate
                bytes.add(rim);
                boolean immResolved = true;
                long immVal = 0;
                
                // Using absolute value
                switch(sourceSize) {
                    case 1:
                        // byte
                        immVal = expectByte(this.source, resolver);
                        bytes.add((byte) immVal);
                        break;
                    
                    case 2:
                        // word
                        immVal = expectWord(this.source, resolver);
                        ASMUtil.addBytes(bytes, immVal, 2);
                        break;
                    
                    case 3, 4:
                        // dword
                        if(this.source.location() instanceof ASMReference ref && ref.getType() == ReferenceType.NORMAL) {
                            // Unresolved, allowed
                            immResolved = false;
                            resolver.placeReference(ref.getName(), bytes.size());
                            ASMUtil.addBytes(bytes, 0, 4);
                        } else {
                            // Not unresolved
                            immVal = expectDword(this.source, resolver);
                            ASMUtil.addBytes(bytes, immVal, 4);
                        }
                        break;
                    
                    default:
                        throw new IllegalArgumentException("Invalid size for source in " + this);
                }
                
                if(immResolved) {
                    resolver.setImmediate(immVal);
                }
            } else {
                // Nothing else, just add
                bytes.add(rim);
            }
        }
        
        // Non-rim/bio immediate
        boolean hasResolvedImm = false;
        long immVal = 0;
        
        switch(dgroup) {
            case I8, I8_EI8:
                // byte
                hasResolvedImm = true;
                immVal = expectByte(this.source, resolver);
                bytes.add((byte) immVal);
                break;
            
            case I16:
                // word
                hasResolvedImm = true;
                immVal = expectWord(this.source, resolver);
                ASMUtil.addBytes(bytes, immVal, 2);
                break;
            
            case I32:
                // dword, may be unresolved
                if(this.source.location() instanceof ASMReference ref && ref.getType() == ReferenceType.NORMAL) {
                    // Unresolved, allowed
                    resolver.placeReference(ref.getName(), bytes.size());
                    ASMUtil.addBytes(bytes, 0, 4);
                } else {
                    // Not unresolved
                    hasResolvedImm = true;
                    immVal = expectDword(this.source, resolver);
                    ASMUtil.addBytes(bytes, immVal, 4);
                }
                break;
            
            default:
        }
        
        if(hasResolvedImm) {
            resolver.setImmediate(immVal);
        }
        
        // EI8
        if(dgroup.hasEI8) {
            // EI8 comes from ei8 if not null and source otherwise
            if(this.ei8 != null) {
                // ei8 present, use it
                bytes.add(expectByte(this.ei8, resolver));
            } else {
                // Use source otherwise
                bytes.add(expectByte(this.source, resolver));
            }
        }
        
        return bytes;
    }
    
    /**
     * Gets the real size of something
     * @param arg
     * @param res
     * @return
     */
    private int realSize(ASMArgument arg, ASMResolver res) throws UnresolvableException {
        // Does it have a size override
        if(arg.size() > 0) {
            // Yes. use that.
            return arg.size();
        }
        
        // Get size from location
        return switch(arg.location()) {
            case ASMRegister r  -> r.reg().size(); // registers have sizes
            case ASMReference r -> {
                if(r.getType() == ReferenceType.NORMAL) {
                    // Normal references are pointers
                    yield 4;
                } else {
                    // Relative references are values
                    yield ASMUtil.getWidth(r.getValue(res), true, useStrict(), true, true);
                }
            }
            case ASMValue v     -> ASMUtil.getWidth(v.getValue(res), true, useStrict(), true, true); // values
            case ASMMemory _    -> 0; // memory is unknown without an override
            default -> throw new IllegalArgumentException("Unexpected ASMLocation: " + arg.location());
        };
    }
    
    /**
     * Return true if immediate should be strictly signed
     * @return
     */
    private boolean useStrict() {
        return switch(this.op) {
            case JMP_I8, JMP_I16, JMP_I32, JMP_RIM, JCC_I8, JCC_RIM,
                 JC_I8, JNC_I8, JS_I8, JNS_I8, JO_I8, JNO_I8, JZ_I8, JNZ_I8,
                 JA_I8, JBE_I8, JG_I8, JGE_I8, JL_I8, JLE_I8,
                 JC_RIM, JNC_RIM, JS_RIM, JNS_RIM, JO_RIM, JNO_RIM, JZ_RIM, JNZ_RIM,
                 JA_RIM, JBE_RIM, JG_RIM, JGE_RIM, JL_RIM, JLE_RIM,
                 CALL_I8, CALL_I16, CALL_I32, CALL_RIM, MOVS_RIM
                -> true;
            default -> false;
        };
    }
    
    /**
     * Expect a resolved dword from an argument
     * @param arg
     * @param res
     * @return
     * @throws UnresolvableException
     */
    private long expectDword(ASMArgument arg, ASMResolver res) throws UnresolvableException {
        // make sure the override isn't wrong
        if(arg.size() > 0 && arg.size() != 4) {
            // Invalid size override
            LOG.severe("Argument " + arg + " in " + this + " is not a dword");
            throw new UnresolvableException();
        }
        
        // expect a value exists
        return expectResolvedValue(arg.location(), res);
    }
    
    /**
     * Expect a resolved word from an argument
     * @param arg
     * @param res
     * @return
     * @throws UnresolvableException
     */
    private int expectWord(ASMArgument arg, ASMResolver res) throws UnresolvableException {
        // make sure the override isn't wrong
        if(arg.size() > 0 && arg.size() != 2) {
            // Invalid size override
            LOG.severe("Argument " + arg + " in " + this + " is not a word");
            throw new UnresolvableException();
        }
        
        // expect a value
        long v = expectResolvedValue(arg.location(), res);
        
        // make sure it's the right size
        if(ASMUtil.getWidth(v, true, false, false, false) != 2) {
            LOG.severe("Value " + v + " from " + arg + " in " + this + " does not fit in a word");
            throw new UnresolvableException();
        }
        
        return (int) v;
    }
    
    /**
     * Expect a resolved byte from an argument 
     * @param arg
     * @param res
     * @return
     * @throws UnresolvableException
     */
    private byte expectByte(ASMArgument arg, ASMResolver res) throws UnresolvableException {
        // make sure the override isn't large
        if(arg.size() > 1) {
            // Invalid size override
            LOG.severe("Argument " + arg + " in " + this + " is not a byte");
            throw new UnresolvableException();
        }
        
        // expect a byte
        return expectByte(arg.location(), res);
    }
    
    /**
     * Expect a resolved byte from a location
     * @param loc
     * @param res
     * @return
     * @throws UnresolvableException
     */
    private byte expectByte(ASMLocation loc, ASMResolver res) throws UnresolvableException {
        // expect a value exists
        long v = expectResolvedValue(loc, res);
        
        // make sure it fits in a byte
        if(ASMUtil.getWidth(v, true, false, true, false) != 1) {
            LOG.severe("Value " + v + " from " + loc + " in " + this + " does not fit in a byte");
            throw new UnresolvableException();
        }
        
        return (byte) v;
    }
    
    /**
     * Get the resolved value of a location, throwing an UnresolvableException otherwise
     * @param loc
     * @return
     */
    private long expectResolvedValue(ASMLocation loc, ASMResolver res) throws UnresolvableException {
        // we need a value
        if(loc instanceof ASMValue v) {
            // get the value
            return v.getValue(res);
        } else {
            // oh no
            LOG.severe("Expected resolvable value, got " + loc + " in " + this);
            throw new UnresolvableException();
        }
    }
    
    @Override
    public String toString() {
        // Build from opcode according to what should exist
        StringBuilder sb = new StringBuilder(this.op.toString());
        DecodingGroup dgroup = this.op.dgroup;
        
        if(dgroup.hasDestination) {
            sb.append(" ");
            sb.append(this.destination);
        }
        
        if(dgroup.hasSource) {
            if(dgroup.hasDestination) {
                sb.append(",");
            }
            
            sb.append(" ");
            sb.append(this.source);
        }
        
        if(dgroup.hasEI8) {
            sb.append(", ");
            sb.append(this.ei8);
        }
        
        return sb.toString();
    }
}

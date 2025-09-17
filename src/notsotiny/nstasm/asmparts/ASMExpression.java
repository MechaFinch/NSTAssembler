package notsotiny.nstasm.asmparts;

import notsotiny.nstasm.assembly.ASMResolver;
import notsotiny.nstasm.assembly.UnresolvableException;

/**
 * An expression
 */
public class ASMExpression implements ASMValue {
    
    /**
     * Operation
     */
    public enum ASMOperation {
        
        AND("&", false), OR("|", false), XOR("^", false), NOT("!", true),
        SHL("<<", false), SHR(">>", false), SAR(">>>", false),
        ADD("+", false), SUB("-", false), NEG("-", true), MUL("*", false), DIV("/", false), MOD("%", false);
        
        private String str;
        
        private boolean isSingleArg;
        
        private ASMOperation(String str, boolean isSingleArg) {
            this.str = str;
            this.isSingleArg = isSingleArg;
        }
        
        /**
         * Apply for two argument
         * @param left
         * @param right
         * @return
         */
        public long apply(long left, long right) {
            return switch(this) {
                case AND    -> left & right;
                case OR     -> left | right;
                case XOR    -> left ^ right;
                case SHL    -> left << right;
                case SHR    -> left >>> right;
                case SAR    -> left >> right;
                case ADD    -> left + right;
                case SUB    -> left - right;
                case MUL    -> left * right;
                case DIV    -> left / right;
                case MOD    -> left % right;
                default     -> throw new IllegalArgumentException("Not two-argument: " + this);
            };
        }
        
        /**
         * Apply for one argument
         * @param val
         * @return
         */
        public long apply(long val) {
            return switch(this) {
                case NOT    -> ~val;
                case NEG    -> -val;
                default     -> throw new IllegalArgumentException("Not one-argument: " + this);
            };
        }
        
        public boolean singleArgument() { return this.isSingleArg; }
        
        @Override
        public String toString() { return this.str; }
        
    }
    
    private ASMValue leftValue, rightValue;
    
    private ASMOperation op;
    
    /**
     * A binary expression
     * @param op
     * @param leftValue
     * @param rightValue
     */
    protected ASMExpression(ASMOperation op, ASMValue leftValue, ASMValue rightValue) {
        this.op = op;
        this.leftValue = leftValue;
        this.rightValue = rightValue;
    }
    
    /**
     * A unary expression
     * @param op must be NEG or NOT
     * @param rightValue
     */
    protected ASMExpression(ASMOperation op, ASMValue rightValue) {
        this(op, null, rightValue);
        
        if(op != ASMOperation.NEG && op != ASMOperation.NOT) {
            throw new IllegalArgumentException("Not a unary operation: " + op);
        }
    }
    
    /**
     * Gets the value of a binary expression. If both sides constant, returns the operation applied to them. Otherwise, returns an expression.
     * null is treated as 0
     * @param op
     * @param leftValue
     * @param rightValue
     * @return
     */
    public static ASMValue valueOf(ASMOperation op, ASMValue leftValue, ASMValue rightValue) {
        // null = 0
        leftValue = (leftValue == null) ? ASMConstant.ZERO : leftValue;
        rightValue = (rightValue == null) ? ASMConstant.ZERO : rightValue;
        
        if(leftValue instanceof ASMConstant lc && rightValue instanceof ASMConstant rc) {
            return new ASMConstant(op.apply(lc.getValue(), rc.getValue()));
        } else {
            // Handle some no-ops
            switch(op) {
                case ADD, OR:
                    // x (+, |) 0 = x
                    if(leftValue.equals(ASMConstant.ZERO)) { 
                        return rightValue;
                    } else if(rightValue.equals(ASMConstant.ZERO)) {
                        return leftValue;
                    }
                    break;
                    
                case SHL, SHR, SAR, SUB:
                    // x (<<, >>, >>>, -) 0 = x, not commutative
                    if(rightValue.equals(ASMConstant.ZERO)) {
                        return leftValue;
                    }
                    break;
                    
                case AND:
                    // x & 0 = 0
                    if(leftValue.equals(ASMConstant.ZERO)|| rightValue.equals(ASMConstant.ZERO)) {
                        return ASMConstant.ZERO;
                    }
                    break;
                    
                case MUL:
                    // x * 1 = x
                    if(leftValue.equals(ASMConstant.ONE)) { 
                        return rightValue;
                    } else if(rightValue.equals(ASMConstant.ONE)) {
                        return leftValue;
                    } else if(leftValue.equals(ASMConstant.ZERO) || rightValue.equals(ASMConstant.ZERO)) {
                        // x * 0 = 0
                        return ASMConstant.ZERO;
                    }
                    break;
                
                case DIV:
                    // x / 1 = x, not commutative
                    if(rightValue.equals(ASMConstant.ONE)) {
                        return leftValue;
                    }
                    break;
                    
                default:
            }
            
            return new ASMExpression(op, leftValue, rightValue);
        }
    }
    
    /**
     * Gets the value of a unary expression. If the value is constant, returns the operation applied to it. Otherwise, returns an expression
     * @param op
     * @param rightValue
     * @return
     */
    public static ASMValue valueOf(ASMOperation op, ASMValue rightValue) {
        // null = 0
        rightValue = (rightValue == null) ? ASMConstant.ZERO : rightValue;
        
        if(rightValue instanceof ASMConstant rc) {
            return new ASMConstant(op.apply(rc.getValue()));
        } else {
            return new ASMExpression(op, rightValue);
        }
    }
    
    /**
     * @return true if this expression is constant
     */
    public boolean isConstant() {
        return (this.leftValue == null ? true : this.leftValue instanceof ASMConstant) && this.rightValue instanceof ASMConstant;
    }

    @Override
    public long getValue(ASMResolver resolver) throws UnresolvableException {
        // Special case for local ref - local ref
        if(this.op == ASMOperation.SUB) { 
            try {
                // Try to do normally
                return this.leftValue.getValue(resolver) - this.rightValue.getValue(resolver);
            } catch(UnresolvableException e) {
                // Might be a local offset
                if(this.leftValue instanceof ASMReference lref && this.rightValue instanceof ASMReference rref &&
                   resolver.isLocal(lref) && resolver.isLocal(rref)) {
                    // yes, use local offsets
                    return resolver.getOffset(lref) - resolver.getOffset(rref);
                } else {
                    // no, unresolvable
                    throw e;
                }
            }
        }
        
        // Otherwise go by op
        if(this.op.isSingleArg) {
            return this.op.apply(this.rightValue.getValue(resolver));
        } else {
            return this.op.apply(this.leftValue.getValue(resolver), this.rightValue.getValue(resolver));
        }
    }
    
    public ASMOperation getOp() { return this.op; }
    public ASMValue getLeftValue() { return this.leftValue; }
    public ASMValue getRightValue() { return this.rightValue; }
    
    @Override
    public String toString() {
        return switch(this.op) {
            case NEG, NOT   -> this.op.toString() + this.rightValue.toString();
            default         -> "(" + this.leftValue.toString() + " " + this.op.toString() + " " + this.rightValue.toString() + ")";
        };
    }
    
}
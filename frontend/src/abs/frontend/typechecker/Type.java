package abs.frontend.typechecker;

import abs.frontend.ast.MethodSig;

public abstract class Type {
    public abstract String toString();

    public boolean isReferenceType() {
        return false;
    }

    public boolean isInterfaceType() {
        return false;
    }

    public boolean isDataType() {
        return false;
    }

    public boolean isNullType() {
        return false;
    }

    public boolean isTypeParameter() {
        return false;
    }

    public boolean isUnknownType() {
        return false;
    }

    public boolean isFutureType() {
        return false;
    }

    public boolean isBoolType() {
        return false;
    }

    public boolean isUnitType() {
        return false;
    }

    public boolean isStringType() {
        return false;
    }

    public boolean isIntType() {
        return false;
    }

    public boolean isAnyType() {
        return false;
    }

    public MethodSig lookupMethod(String name) {
        return null;
    }

    public boolean equals(Object o) {
        if (o == null)
            return false;
        if (!(o instanceof Type))
            return false;
        return true;
    }

    public boolean isBoundedType() {
        return false;
    }

    public boolean isAssignable(Type t, boolean considerSubtyping) {
        return isAssignable(t);
    }

    public boolean isAssignable(Type t) {
        if (t == null)
            throw new IllegalArgumentException("t is null");

        if (t.isAnyType())
            return true;

        if (this.equals(t))
            return true;

        if (t.isBoundedType()) {
            BoundedType bt = (BoundedType) t;
            if (bt.hasBoundType())
                return this.isAssignable(bt.getBoundType());
            bt.bindTo(this);
            return true;
        }

        return false;
    }

    public boolean isUnionType() {
        return false;
    }

    public boolean canBeBoundTo(Type t) {
        return false;
    }
}

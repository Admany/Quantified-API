package org.admany.quantified.api.vulkan;

import java.util.Arrays;

public final class McDensityProgram {

    public static final int STRIDE = 4;
    public static final int MAX_INSTRUCTIONS = 256;
    public static final int REGISTER_COUNT = 64;
    public static final int RESULT_REGISTER = 0;
    public static final int X_REGISTER = 1;
    public static final int Y_REGISTER = 2;
    public static final int Z_REGISTER = 3;

    public static final int OP_END = 0;
    public static final int OP_CONST = 1;
    public static final int OP_X = 2;
    public static final int OP_Y = 3;
    public static final int OP_Z = 4;
    public static final int OP_ADD = 10;
    public static final int OP_SUB = 11;
    public static final int OP_MUL = 12;
    public static final int OP_DIV = 13;
    public static final int OP_MIN = 14;
    public static final int OP_MAX = 15;
    public static final int OP_ABS = 16;
    public static final int OP_SQUARE = 17;
    public static final int OP_CUBE = 18;
    public static final int OP_CLAMP = 19;
    public static final int OP_LERP = 20;
    public static final int OP_NEG = 21;
    public static final int OP_NEG_MUL = 22;
    public static final int OP_GE = 23;
    public static final int OP_LT = 24;
    public static final int OP_AUX = 25;

    private static final int TEMP_0 = 4;
    private static final int TEMP_1 = 5;
    private static final int TEMP_2 = 6;

    private final float[] encoded;
    private final int instructionCount;

    private McDensityProgram(float[] encoded, int instructionCount) {
        this.encoded = encoded;
        this.instructionCount = instructionCount;
    }

    public static Builder builder() {
        return new Builder();
    }

    public float[] encoded() {
        return encoded.clone();
    }

    public int instructionCount() {
        return instructionCount;
    }

    public static final class Builder {
        private float[] encoded = new float[STRIDE * 16];
        private int instructionCount;
        private boolean ended;

        private Builder() {
        }

        public Builder constant(float value) {
            return constant(RESULT_REGISTER, value);
        }

        public Builder x() {
            return x(RESULT_REGISTER);
        }

        public Builder y() {
            return y(RESULT_REGISTER);
        }

        public Builder z() {
            return z(RESULT_REGISTER);
        }

        public Builder add(float value) {
            return constant(TEMP_0, value).binary(OP_ADD, RESULT_REGISTER, RESULT_REGISTER, TEMP_0);
        }

        public Builder subtract(float value) {
            return constant(TEMP_0, value).binary(OP_SUB, RESULT_REGISTER, RESULT_REGISTER, TEMP_0);
        }

        public Builder multiply(float value) {
            return constant(TEMP_0, value).binary(OP_MUL, RESULT_REGISTER, RESULT_REGISTER, TEMP_0);
        }

        public Builder divide(float value) {
            return constant(TEMP_0, value).binary(OP_DIV, RESULT_REGISTER, RESULT_REGISTER, TEMP_0);
        }

        public Builder min(float value) {
            return constant(TEMP_0, value).binary(OP_MIN, RESULT_REGISTER, RESULT_REGISTER, TEMP_0);
        }

        public Builder max(float value) {
            return constant(TEMP_0, value).binary(OP_MAX, RESULT_REGISTER, RESULT_REGISTER, TEMP_0);
        }

        public Builder abs() {
            return unary(OP_ABS, RESULT_REGISTER, RESULT_REGISTER);
        }

        public Builder square() {
            return unary(OP_SQUARE, RESULT_REGISTER, RESULT_REGISTER);
        }

        public Builder cube() {
            return unary(OP_CUBE, RESULT_REGISTER, RESULT_REGISTER);
        }

        public Builder clamp(float min, float max) {
            return max(min).min(max);
        }

        public Builder lerp(float target, float alpha) {
            return constant(TEMP_0, target)
                .constant(TEMP_1, alpha)
                .binary(OP_SUB, TEMP_2, TEMP_0, RESULT_REGISTER)
                .binary(OP_MUL, TEMP_2, TEMP_2, TEMP_1)
                .binary(OP_ADD, RESULT_REGISTER, RESULT_REGISTER, TEMP_2);
        }

        public Builder negate() {
            return unary(OP_NEG, RESULT_REGISTER, RESULT_REGISTER);
        }

        public Builder negativeMultiply(float factor) {
            return constant(TEMP_0, factor).binary(OP_NEG_MUL, RESULT_REGISTER, RESULT_REGISTER, TEMP_0);
        }

        public Builder aux(int auxIndex) {
            return aux(RESULT_REGISTER, auxIndex);
        }

        public Builder aux(int dst, int auxIndex) {
            return instruction(OP_AUX, dst, auxIndex, 0);
        }

        public Builder constant(int dst, float value) {
            return instruction(OP_CONST, dst, value, 0.0f);
        }

        public Builder x(int dst) {
            return instruction(OP_X, dst, 0, 0);
        }

        public Builder y(int dst) {
            return instruction(OP_Y, dst, 0, 0);
        }

        public Builder z(int dst) {
            return instruction(OP_Z, dst, 0, 0);
        }

        public Builder unary(int opcode, int dst, int source) {
            return instruction(opcode, dst, source, 0);
        }

        public Builder binary(int opcode, int dst, int left, int right) {
            return instruction(opcode, dst, left, right);
        }

        public Builder end() {
            return instruction(OP_END, RESULT_REGISTER, 0, 0);
        }

        public Builder instruction(int opcode, int dst, int a, int b) {
            return instruction(opcode, dst, (float) a, (float) b);
        }

        public Builder instruction(int opcode, float a, float b, float c) {
            if (ended) {
                throw new IllegalStateException("Density program already ended");
            }
            validateRegisterSlot(a);
            validateSourceRegisters(opcode, b, c);
            if (instructionCount >= MAX_INSTRUCTIONS) {
                throw new IllegalStateException("Density program exceeds " + MAX_INSTRUCTIONS + " instructions");
            }
            ensureCapacity((instructionCount + 1) * STRIDE);
            int offset = instructionCount * STRIDE;
            encoded[offset] = opcode;
            encoded[offset + 1] = a;
            encoded[offset + 2] = b;
            encoded[offset + 3] = c;
            instructionCount++;
            ended = opcode == OP_END;
            return this;
        }

        private static void validateRegisterSlot(float value) {
            int register = (int) value;
            if (register < 0 || register >= REGISTER_COUNT) {
                throw new IllegalArgumentException("Register out of range: " + register);
            }
        }

        private static void validateSourceRegisters(int opcode, float left, float right) {
            switch (opcode) {
                case OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MIN, OP_MAX, OP_NEG_MUL, OP_GE, OP_LT -> {
                    validateRegisterSlot(left);
                    validateRegisterSlot(right);
                }
                case OP_ABS, OP_SQUARE, OP_CUBE, OP_NEG -> validateRegisterSlot(left);
                case OP_AUX -> {
                    if ((int) left < 0) {
                        throw new IllegalArgumentException("Aux index must be non-negative: " + (int) left);
                    }
                }
                default -> {
                }
            }
        }

        public McDensityProgram build() {
            if (!ended) {
                end();
            }
            return new McDensityProgram(Arrays.copyOf(encoded, instructionCount * STRIDE), instructionCount);
        }

        private void ensureCapacity(int required) {
            if (encoded.length >= required) {
                return;
            }
            int next = encoded.length;
            while (next < required) {
                next *= 2;
            }
            encoded = Arrays.copyOf(encoded, next);
        }
    }
}

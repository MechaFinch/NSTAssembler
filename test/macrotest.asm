
;
; File for testing macro features
;

%define definition_macro XOR D, C
%define definition_value 654

%macro no_args_macro() 9999
%macro single_part_macro(x) [BP + (x + 8)]
%macro single_line_macro(x) ADD A, x
%macro multi_line_macro(w, x, y, z):
		MOVW w, z
	%%macro_label:
		MOVW [x], y
		ADD x, 4
		SUB w, 4
		JNZ %%macro_label
%endmacro

%define multi_define_1 definition_macro
%define multi_define_2 multi_define_1

%include "asdf.asm" as asdf

;definition_macro:
asdf:
	MOV D, 1
	MOV C, D
	ADD C, byte 32
	multi_define_2
	CALL asdf.fdsa
	PUSH ptr example
	JMP asdf.aksdlfjasd

example:

	JNZ .example
	JZ.A8 .example
	PCMOV4Z A, B
	JNZ.E4 .example
	JMP .example
	JMP #.example

.example:
	MOV A, B
	MOV C, [D:A]
	MOV J, [D:A - 2 + I]
	MOV I, [D:A + 2*J + 15]
	MOV D, [D:A + (3-1)*I - 55697 + definition_value]
	definition_macro
	MOV A, definition_value
	MOV D, single_part_macro(5)
	MOVW B:C, single_part_macro(16)
	single_line_macro(15)
	multi_line_macro(J:I, L:K, D:A, 56)
	JNZ .example
	
	; test everything that can go in an expression
	ADD A, 1 | 2 ^ 3 & (4 << 5 >> 6 >>> 7) + 8 - 9 * 10 / 11 % 12 + -13 + !14 + @example + $example - "6" + '5'
	
.lol:
	
	repeat (.lol - .example) / 16, JMP .lol
	
	%include "asdf.asm" as fdsa
	%libname test
	%org 69420
	;%nlo
	;%privileged

	db 5, 6, 7, "asdlkfjaskldfjalkdsf"
	dw 8, 9, 10, "lasdkjfasld"
	dp 11, 12, 13, "lfkklsfkksksks"
	resb 512
	resw 256
	resp 128
	
	resb 65536
	JMP .lol

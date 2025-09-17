
;
; Automata
; Basic Cellular Automata
;

%define SCREEN_ADDRESS 0xF002_0000
%define SCREEN_WIDTH 320
%define SCREEN_HEIGHT 240
%define AREA_WIDTH 128
%define AREA_HEIGHT 128

%define RANDOM 0xF004_0000

%include "simvideo/gutil.asm" as gutil

%org 0
ivt:
	dp entry
	repeat (1024 - (# - ivt)) / 4, dp void

void:
	IRET
	
entry:
	; cell colors
	PUSH ptr 0x00_FF_FF_FF
	PUSH byte 0x01
	CALLA gutil.set_color
	
	PUSH ptr 0x00_00_00_00
	PUSH byte 0x00
	CALLA gutil.set_color
	ADD SP, 10
	
	; init
	MOV J:I, SCREEN_ADDRESS
	MOV L, AREA_WIDTH
	MOV K, AREA_HEIGHT
.iloop:
	MOV AL, [RANDOM]
	AND AL, 1
	MOV [J:I], AL
	
	INC J:I
	DEC L
	JNZ .iloop
	
	MOV L, AREA_WIDTH
	ADD J:I, SCREEN_WIDTH - AREA_WIDTH
	
	DEC K
	JNZ .iloop
	
	MOV A, PF
	; OR A, 1
	MOV PF, A
	
	; run
.loop:
	CALL step
	
	;HLT
	JMP .loop

; void step()
; step
step:
	PUSH BP
	MOVW BP, SP
	
	PUSH J:I
	PUSH L:K
	
	; B:C = intermediate pointer
	; J:I = center pointer
	; K = x
	; L = y
	MOVW B:C, intermediate
	MOVW J:I, SCREEN_ADDRESS
	MOVW L:K, 0

.uloop:
	; valid squares mask in DH
	; from msb to lsb, tl tu tr ml mr bl bm br
	MOVW D:A, -1
	CMP K, 0				; if x=0, left invalid
	CMOVE DL, 0b01101011
	
	CMP K, AREA_WIDTH - 1	; if x=w, right invalid
	CMOVE DL, 0b11010110
	
	CMP L, 0				; if y=0, top invalid
	CMOVE DH, 0b00011111
	
	CMP L, AREA_HEIGHT - 1	; if y=h, bottom invalid
	CMOVE DH, 0b11111000
	
	AND DH, DL
	
	; cell value in A
	MOVZ A, [J:I]
	
	; sum surrounding to DL
	%macro uloop_sum(next, offs):
		SHL DH, 1
		JNC next
		ADD DL, [J:I + offs]
	%endmacro
	
	MOV DL, 0
.tl:	uloop_sum(.tm, -(SCREEN_WIDTH + 1))
.tm:	uloop_sum(.tr, -SCREEN_WIDTH)
.tr:	uloop_sum(.ml, -(SCREEN_WIDTH - 1))
.ml:	uloop_sum(.mr, -1)
.mr:	uloop_sum(.bl, 1)
.bl:	uloop_sum(.bm, SCREEN_WIDTH - 1)
.bm:	uloop_sum(.br, SCREEN_WIDTH)
.br:	uloop_sum(.next, SCREEN_WIDTH + 1)

.next:
	; update ccell in intermediate array
	MOVZ D, DL
	
	PUSH I
	MOV I, D
	MOVW D:A, [table_table + A*4]	; get transition table pointer according to current cell value
	MOV AL, [D:A + I]				; get next value from transition table
	MOV [B:C], AL					; place new value
	POP I
	
	; increment pointers
	INC B:C
	INC J:I
	
	INC K
	CMP K, AREA_WIDTH
	JB .uloop
	
	; wrap on screen
	MOV K, 0
	ADD J:I, SCREEN_WIDTH - AREA_WIDTH
	
	INC L
	CMP L, AREA_HEIGHT
	JB .uloop
	
	; copy intermediate to screen
	MOV B:C, intermediate
	MOV J:I, SCREEN_ADDRESS
	MOV L:K, 0
	
.cloop:
	MOV AL, [B:C]
	MOV [J:I], AL
	
	INC J:I
	INC B:C
	
	INC K
	CMP K, AREA_WIDTH
	JB .cloop
	
	MOV K, 0
	ADD J:I, SCREEN_WIDTH - AREA_WIDTH
	
	INC L
	CMP L, AREA_HEIGHT
	JB .cloop
	
	; done
	POP L:K
	POP J:I
	POP BP
	RET

table_table:
	dp trans_table_00
	dp trans_table_01

trans_table_00:
	db 0, 0, 0, 1, 0, 0, 0, 0, 0

trans_table_01:
	db 0, 0, 1, 1, 0, 0, 0, 0, 0

intermediate:
	resb AREA_WIDTH * AREA_HEIGHT
	

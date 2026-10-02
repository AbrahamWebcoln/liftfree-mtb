// Monkey C's Number.toString does not round-trip the minimum signed integer
// on the tested SDK. Emit that one protocol sentinel explicitly.
function lfWireInteger(value) {
    if(value == -2147483648) { return "-2147483648"; }
    return value.toString();
}

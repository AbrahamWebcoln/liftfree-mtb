using Toybox.Lang;

// Wire values must have deterministic decimal representations.
function lfWireInteger(value) {
    if(value == -2147483648) { return "-2147483648"; }
    return value.toString();
}

// Received strings are distinct objects even when their text is identical.
function lfTextEquals(left,right) {
    return (left instanceof Lang.String) && left.equals(right);
}

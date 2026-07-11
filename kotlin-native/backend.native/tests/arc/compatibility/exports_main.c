#include "compat_api.h"

int main(void) {
    return arc_compat_add(19, 23) == 42 ? 0 : 1;
}

# InterpreterProxy Bindings

To regenerate the checked-in InterpreterProxy bindings:

1. Download [jextract](https://jdk.java.net/jextract/) and run it with:

    ```bash
    jextract @./src/de.hpi.swa.trufflesqueak.interpreterproxy.bindings/jextract.args
    ```
2. Re-apply [this Windows compatibility fix](https://github.com/hpi-swa/trufflesqueak/commit/86c1be4f7a708569e239edbf7f4f05417644bd3c).

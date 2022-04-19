/*
 * Copyright (c) 2017-2022 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2021-2022 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak;

import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionStability;
import org.graalvm.options.OptionValues;

import com.oracle.truffle.api.Option;
import com.oracle.truffle.api.TruffleLanguage.Env;

import de.hpi.swa.trufflesqueak.shared.SqueakLanguageConfig;
import de.hpi.swa.trufflesqueak.shared.SqueakLanguageOptions;

@Option.Group(SqueakLanguageConfig.ID)
public final class SqueakOptions {

    @Option(name = SqueakLanguageOptions.IMAGE_PATH, category = OptionCategory.USER, stability = OptionStability.STABLE, help = SqueakLanguageOptions.IMAGE_PATH_HELP, usageSyntax = "path/to/your.image")//
    public static final OptionKey<String> ImagePath = new OptionKey<>("");

    @Option(name = SqueakLanguageOptions.IMAGE_ARGUMENTS, category = OptionCategory.USER, stability = OptionStability.EXPERIMENTAL, help = SqueakLanguageOptions.IMAGE_ARGUMENTS_HELP, usageSyntax = "'arg1 arg2 ...'")//
    public static final OptionKey<String> ImageArguments = new OptionKey<>("");

    @Option(name = SqueakLanguageOptions.HEADLESS, category = OptionCategory.USER, stability = OptionStability.STABLE, help = SqueakLanguageOptions.HEADLESS_HELP, usageSyntax = "true|false")//
    public static final OptionKey<Boolean> Headless = new OptionKey<>(true);

    @Option(name = SqueakLanguageOptions.INTERCEPT_MESSAGES, category = OptionCategory.INTERNAL, stability = OptionStability.EXPERIMENTAL, help = SqueakLanguageOptions.INTERCEPT_MESSAGES_HELP, //
                    usageSyntax = "'Object>>becomeForward:,Behavior>>allInstances,...'")//
    public static final OptionKey<String> InterceptMessages = new OptionKey<>("");

    @Option(name = SqueakLanguageOptions.QUIET, category = OptionCategory.USER, stability = OptionStability.STABLE, help = SqueakLanguageOptions.QUIET_HELP, usageSyntax = "false|true")//
    public static final OptionKey<Boolean> Quiet = new OptionKey<>(false);

    @Option(name = SqueakLanguageOptions.INTERRUPTS, category = OptionCategory.USER, stability = OptionStability.STABLE, help = SqueakLanguageOptions.INTERRUPTS_HELP, usageSyntax = "false|true")//
    public static final OptionKey<Boolean> Interrupts = new OptionKey<>(false);

    @Option(name = SqueakLanguageOptions.SIGNAL_INPUT_SEMAPHORE, category = OptionCategory.INTERNAL, stability = OptionStability.EXPERIMENTAL, help = SqueakLanguageOptions.SIGNAL_INPUT_SEMAPHORE_HELP, usageSyntax = "false|true")//
    public static final OptionKey<Boolean> SignalInputSemaphore = new OptionKey<>(false);

    @Option(name = SqueakLanguageOptions.STARTUP, category = OptionCategory.INTERNAL, stability = OptionStability.EXPERIMENTAL, help = SqueakLanguageOptions.STARTUP_HELP, usageSyntax = "false|true")//
    public static final OptionKey<Boolean> Startup = new OptionKey<>(false);

    @Option(name = SqueakLanguageOptions.TESTING, category = OptionCategory.INTERNAL, stability = OptionStability.STABLE, help = SqueakLanguageOptions.TESTING_HELP, usageSyntax = "false|true")//
    public static final OptionKey<Boolean> Testing = new OptionKey<>(false);

    private SqueakOptions() { // no instances
    }

    public static OptionDescriptors createDescriptors() {
        return new SqueakOptionsOptionDescriptors();
    }

    public static final class SqueakContextOptions {
        public final String imagePath;
        public final String[] imageArguments;
        public final boolean isHeadless;
        public final boolean isQuiet;
        public final boolean disableInterruptHandler;
        public final boolean disableStartup;
        public final boolean isTesting;
        public final boolean signalInputSemaphore;

        public SqueakContextOptions(final Env env) {
            final OptionValues options = env.getOptions();
            imagePath = options.get(ImagePath).isEmpty() ? null : options.get(ImagePath);
            imageArguments = options.get(ImageArguments).isEmpty() ? new String[0] : options.get(ImageArguments).split(",");
            isHeadless = options.get(Headless);
            isQuiet = options.get(Quiet);
            disableInterruptHandler = options.get(Interrupts);
            disableStartup = options.get(Startup);
            signalInputSemaphore = options.get(SignalInputSemaphore);
            isTesting = options.get(Testing);
        }
    }
}

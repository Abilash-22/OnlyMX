package app.template.patches.mxplayer

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_MX_PLAYER_AD
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

@Suppress("unused")
val removeAdsAndBloatPatch = bytecodePatch(
    name = "Remove ads & bloat",
    description = "Removes ads and online extras like Live TV, Tube, Shorts, and Games while keeping Web Series.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MX_PLAYER_AD)

    dependsOn(mxIntegrityCorePatch)

    extendWith("extensions/mxplayer.mpe")

    execute {
        // =========================================================================
        // 1. Monetize-v2 Ad Engine & Placement Managers (3.3.1 / 3.4.0 / 3.4.1)
        // =========================================================================
        run {
            // AdInitializer: stub both non-constructor `()V` warm-up methods (`e` + `A5`/`z5`)
            // while keeping `c(App)` / `f(App)` intact so `AdManagerV2` singletons stay non-null.
            val adInitClass = AdInitializerNameFingerprint.classDef
            val adInitWarmups = adInitClass.methods.filter {
                it.parameterTypes.isEmpty() &&
                    it.returnType == "V" &&
                    it.name != "<init>" &&
                    it.name != "<clinit>"
            }
            if (adInitWarmups.isEmpty()) {
                throw PatchException("Failed to locate warm-up methods on ${adInitClass.type}")
            }
            adInitWarmups.toList().forEach(adInitClass::returnVoid)

            // SharedStateManager: `b()Z` delegates to `A0()Z` (3.3.1/3.4.0) or `z0()Z` (3.4.1).
            // Resolve the target dynamically from `b()Z` and force both to `false`.
            val sharedStateClass = SharedStateManagerInitFingerprint.classDef
            val isAdEnabledBridge = sharedStateClass.requireMethod("b", "Z", emptyList())
            val isAdEnabledTargetName = isAdEnabledBridge.instructions
                .filterIsInstance<ReferenceInstruction>()
                .mapNotNull { it.reference as? MethodReference }
                .singleOrNull { it.definingClass == sharedStateClass.type && it.parameterTypes.isEmpty() && it.returnType == "Z" }
                ?.name
                ?: throw PatchException("Failed to resolve a unique SharedStateManager->b()Z target on ${sharedStateClass.type}")
            sharedStateClass.returnBoolean(isAdEnabledBridge, false)
            sharedStateClass.returnBoolean(
                sharedStateClass.requireMethod(isAdEnabledTargetName, "Z", emptyList()),
                false,
            )

            // SharedStateManager's non-`m` `()V` method (`P` in 3.3.1, `O` in 3.4.0/3.4.1) is the
            // mediation SDK bootstrap method name shared with `AdManagerV2`.
            val sdkBootstrapName = sharedStateClass.methods.singleOrNull {
                it.parameterTypes.isEmpty() &&
                    it.returnType == "V" &&
                    it.name != "<init>" &&
                    it.name != "<clinit>" &&
                    it.name != "m"
            }?.name ?: throw PatchException("Failed to locate a unique mediation bootstrap method on ${sharedStateClass.type}")

            val adManagerClass = AdManagerV2InitFingerprint.classDef
            adManagerClass.returnVoid(adManagerClass.requireMethod(sdkBootstrapName, "V", emptyList()))

            // BannerManager: `b()Z -> false`.
            val bannerClass = BannerManagerEnabledFingerprint.classDef
            bannerClass.returnBoolean(BannerManagerEnabledFingerprint.method, false)

            // PanelNative: `"isNotification"` load method (`T`/`U`) -> `false` and the recursive
            // readiness/dispatch `()Z` method (>20 instructions, `Y` in 3.3.1 / `Z` in 3.4.x) -> `false`.
            val panelNativeClass = PanelNativeLoadFingerprint.classDef
            panelNativeClass.returnBoolean(PanelNativeLoadFingerprint.method, false)
            val panelReadyMethod = panelNativeClass.methods.firstOrNull { candidate ->
                candidate.parameterTypes.isEmpty() &&
                    candidate.returnType == "Z" &&
                    (candidate.implementation?.instructions?.count() ?: 0) > 20 &&
                    candidate.instructions.any { insn ->
                        val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                        ref?.definingClass == panelNativeClass.type && ref.name == candidate.name
                    }
            } ?: throw PatchException("Failed to locate PanelNative readiness method on ${panelNativeClass.type}")
            panelNativeClass.returnBoolean(panelReadyMethod, false)

            // InterstitialV2: show (`h`/`i`) -> `false`, `a(...,Z,JSONObject)V` -> `void`,
            // `b(...,Z,JSONObject)Z` -> `false`, and both `()Z` readiness checks reading `this.d` -> `false`.
            val interstitialClass = InterstitialShowFingerprint.classDef
            interstitialClass.returnBoolean(InterstitialShowFingerprint.method, false)
            interstitialClass.returnVoid(
                interstitialClass.methods.first { it.name == "a" && it.returnType == "V" && it.parameterTypes.size == 3 },
            )
            interstitialClass.returnBoolean(
                interstitialClass.methods.first { it.name == "b" && it.returnType == "Z" && it.parameterTypes.size == 3 },
                false,
            )
            val interstitialReadyChecks = interstitialClass.methods.filter { candidate ->
                candidate.parameterTypes.isEmpty() &&
                    candidate.returnType == "Z" &&
                    candidate.instructions.any { insn ->
                        val fRef = (insn as? ReferenceInstruction)?.reference as? FieldReference
                        fRef?.definingClass == interstitialClass.type && fRef.name == "d"
                    }
            }
            if (interstitialReadyChecks.isEmpty()) {
                throw PatchException("Failed to locate InterstitialV2 readiness methods on ${interstitialClass.type}")
            }
            interstitialReadyChecks.toList().forEach { interstitialClass.returnBoolean(it, false) }

            // RewardedAdPlacement: `(AdCall)Z` (`q`/`s`) -> `false`, `b(AdCall,Z,JSONObject)Z` -> `false`,
            // `(Z)V` referencing `AdCall` (`r`/`u`) -> `void`, and `()Z` readiness checks (`k,p`/`l,r`) -> `false`.
            val rewardedClass = RewardedAdPlacementInitFingerprint.classDef
            rewardedClass.returnBoolean(
                rewardedClass.methods.first {
                    it.returnType == "Z" && it.parameterTypes == listOf("Lcom/mxplay/monetize/v2/loader/AdCall;")
                },
                false,
            )
            rewardedClass.returnBoolean(
                rewardedClass.methods.first { it.name == "b" && it.returnType == "Z" && it.parameterTypes.size == 3 },
                false,
            )
            val rewardedPreload = rewardedClass.methods.firstOrNull { candidate ->
                candidate.returnType == "V" &&
                    candidate.parameterTypes == listOf("Z") &&
                    candidate.instructions.any { insn ->
                        val fRef = (insn as? ReferenceInstruction)?.reference as? FieldReference
                        fRef?.definingClass == "Lcom/mxplay/monetize/v2/loader/AdCall;"
                    }
            } ?: throw PatchException("Failed to locate RewardedAdPlacement preload method on ${rewardedClass.type}")
            rewardedClass.returnVoid(rewardedPreload)
            rewardedClass.methods
                .filter { it.parameterTypes.isEmpty() && it.returnType == "Z" }
                .toList()
                .forEach { rewardedClass.returnBoolean(it, false) }

            // TrayNative: `b(AdCall,Z,JSONObject)Z -> false`.
            val trayNativeClass = TrayNativeInitFingerprint.classDef
            trayNativeClass.returnBoolean(
                trayNativeClass.methods.first { it.name == "b" && it.returnType == "Z" && it.parameterTypes.size == 3 },
                false,
            )

            // VideoRollManager: return MX Player's built-in non-null disabled roll fallback field from `a(String)`.
            val videoRollClass = VideoRollManagerInitFingerprint.classDef
            val lookupMethod = videoRollClass.methods.first {
                it.name == "a" && it.parameterTypes == listOf("Ljava/lang/String;")
            }
            val dummyFieldRef = lookupMethod.instructions
                .filterIsInstance<ReferenceInstruction>()
                .mapNotNull { it.reference as? FieldReference }
                .firstOrNull { it.definingClass == videoRollClass.type && it.type != "Z" && !it.type.startsWith("Ljava/") }
                ?: throw PatchException("Failed to locate disabled VideoRoll fallback field on ${videoRollClass.type}")
            videoRollClass.replaceMethodBody(
                method = lookupMethod,
                localRegisters = 1,
                smaliInstructions = """
                    iget-object v0, p0, $dummyFieldRef
                    return-object v0
                """.trimIndent(),
            )

            // OpenAds, WelcomeAdFeatureFlag & LocalOnlyExitAdTest.
            val openAdsClass = OpenAdsShowWelcomeFingerprint.classDef
            openAdsClass.returnVoid(OpenAdsShowWelcomeFingerprint.method)
            openAdsClass.returnVoid(
                openAdsClass.requireMethod("f", "V", listOf("Landroid/app/Activity;")),
            )

            // WelcomeAdFeatureFlag (`Ltel;`): `b()Z` and `a()Z` are inverted disable flags (`return !z;`,
            // where `true` = Welcome Ad / cache DISABLED). Returning `true` sets `ActivityWelcomeMX.q0 = !tel.b()`
            // to `false`, completely preventing `WelcomeScreenAdFragment` (`yel`, "MX Player / Amazon") from
            // ever displaying on cold start or stacking/deadlocking across multi-theme `recreate()` cycles!
            val welcomeFlagClass = WelcomeAdEnabledFingerprint.classDef
            welcomeFlagClass.returnBoolean(WelcomeAdEnabledFingerprint.method, true)
            welcomeFlagClass.returnBoolean(
                welcomeFlagClass.requireMethod("a", "Z", emptyList()),
                true,
            )

            // Defense-in-depth: disarm `AppOpenAdManager.p()V` & `o()Z` so no caller can ever trigger
            // Welcome-Ad Room DB queries or CloudFront `HEAD` validation coroutines.
            val appOpenAdManagerClass = AppOpenAdManagerPrepareFingerprint.classDef
            appOpenAdManagerClass.returnVoid(AppOpenAdManagerPrepareFingerprint.method)
            appOpenAdManagerClass.returnBoolean(
                appOpenAdManagerClass.requireMethod("o", "Z", emptyList()),
                false,
            )

            val exitAdClass = LocalOnlyExitAdEnableFingerprint.classDef
            exitAdClass.returnBoolean(LocalOnlyExitAdEnableFingerprint.method, false)

            // ActivityScreen player ad hooks (resolved structurally across 3.3.1 / 3.4.0 / 3.4.1):
            // - pre-roll check (`Gb`/`Fb`, `"key_has_check_video_ad"`) -> void
            // - player banner (`Ob`/`Nb`, `"bannerForPlayer"`) -> void
            // - mid-roll progress check (`t9`/`s9` `(I, I)V` reading `a5`) -> void
            // - pause hook (`o9`/`n9`, `"local_pause_recomm"`) -> `super.${method.name}()V` only
            val screenClass = ActivityScreenInitFingerprint.classDef
            val preRollMethod = screenClass.methods.firstOrNull {
                it.parameterTypes.isEmpty() && it.returnType == "V" && it.containsString("key_has_check_video_ad")
            } ?: throw PatchException("ActivityScreen pre-roll method not found")
            val playerBannerMethod = screenClass.methods.firstOrNull {
                it.parameterTypes.isEmpty() && it.returnType == "V" && it.containsString("bannerForPlayer")
            } ?: throw PatchException("ActivityScreen bannerForPlayer method not found")
            val midRollMethod = screenClass.methods.firstOrNull { candidate ->
                candidate.parameterTypes == listOf("I", "I") &&
                    candidate.returnType == "V" &&
                    candidate.instructions.any { insn ->
                        val fRef = (insn as? ReferenceInstruction)?.reference as? FieldReference
                        fRef?.definingClass == screenClass.type && fRef.name == "a5"
                    }
            } ?: throw PatchException("ActivityScreen mid-roll (II)V method not found")
            val pauseHookMethod = screenClass.methods.firstOrNull {
                it.parameterTypes.isEmpty() && it.returnType == "V" && it.containsString("local_pause_recomm")
            } ?: throw PatchException("ActivityScreen pause recommendation method not found")
            val subscribeEntryMethod = screenClass.methods.firstOrNull { candidate ->
                candidate.parameterTypes.isEmpty() &&
                    candidate.returnType == "V" &&
                    candidate.instructions.any { insn ->
                        val ref = (insn as? ReferenceInstruction)?.reference
                        (ref as? TypeReference)?.type == "Lcom/airbnb/lottie/LottieAnimationView;" ||
                            (ref as? MethodReference)?.definingClass == "Lcom/airbnb/lottie/LottieAnimationView;"
                    }
            } ?: throw PatchException("ActivityScreen Lottie subscribe-entry method not found")
            val pauseAdSetupMethod = screenClass.methods.firstOrNull {
                it.parameterTypes.isEmpty() && it.returnType == "V" && it.containsString("preloadCustom")
            } ?: throw PatchException("ActivityScreen pause-ad setup method not found")
            val pauseAdRefreshMethod = screenClass.methods.firstOrNull { candidate ->
                candidate.parameterTypes.isEmpty() &&
                    candidate.returnType == "V" &&
                    candidate.instructions.any { insn ->
                        val fRef = (insn as? ReferenceInstruction)?.reference as? FieldReference
                        fRef?.definingClass == "Lcom/mxplay/monetize/v2/nativead/LoadedStatue;"
                    }
            } ?: throw PatchException("ActivityScreen pause-ad refresh method not found")
            val localToOnlineRecomGate = screenClass.methods.firstOrNull { candidate ->
                candidate.parameterTypes.isEmpty() &&
                    candidate.returnType == "Z" &&
                    candidate.instructions.any { insn ->
                        val mRef = (insn as? ReferenceInstruction)?.reference as? MethodReference
                        mRef?.name == "isLocalToOnlineRecom" && mRef.returnType == "Z"
                    }
            } ?: throw PatchException("ActivityScreen isLocalToOnlineRecom gate method not found")
            val progressChangedMethod = screenClass.requireMethod(
                "onProgressChanged",
                "V",
                listOf("Landroid/widget/SeekBar;", "I", "Z"),
            )

            screenClass.returnVoid(preRollMethod)
            screenClass.returnVoid(playerBannerMethod)
            screenClass.returnVoid(midRollMethod)
            screenClass.returnVoid(subscribeEntryMethod)
            screenClass.returnVoid(pauseAdSetupMethod)
            screenClass.returnVoid(pauseAdRefreshMethod)
            screenClass.returnBoolean(localToOnlineRecomGate, false)
            val pauseMethodName = pauseHookMethod.name
            screenClass.replaceMethodBody(
                method = pauseHookMethod,
                localRegisters = 0,
                smaliInstructions = """
                    invoke-super {p0}, $BASE_ACTIVITY_SCREEN_CLASS->$pauseMethodName()V
                    return-void
                """.trimIndent(),
            )
            screenClass.replaceMethodBody(
                method = progressChangedMethod,
                localRegisters = 0,
                smaliInstructions = """
                    invoke-super {p0, p1, p2, p3}, $BASE_ACTIVITY_SCREEN_CLASS->onProgressChanged(Landroid/widget/SeekBar;IZ)V
                    return-void
                """.trimIndent(),
            )

            // Disarm `playback` / `playback_end` (`localStarPlaySP`) background network telemetry
            // posted on video start and completion while keeping local stream history intact.
            val starPlayTelemetryClass = PlaybackStarPlayTelemetryFingerprint.classDef
            starPlayTelemetryClass.returnVoid(PlaybackStarPlayTelemetryFingerprint.method)
        }

        // =========================================================================
        // 3. GlobalConfig Web Series Only Mode & Feature Gates
        // =========================================================================
        run {
            val gc = GlobalConfigClinitFingerprint.classDef

            // Disable pure-local lock (`j -> false`, `C -> true`), keep Local Music (`B -> true`),
            // enable India region (`i -> true`), main OTT Video tab (`F -> true`), and OTT downloads (`D -> true`).
            gc.returnBoolean(gc.requireMethod("j", "Z", emptyList()), false) // isPureLocalPhone
            gc.returnBoolean(gc.requireMethod("C", "Z", emptyList()), true)  // !isPureLocalPhone
            gc.returnBoolean(gc.requireMethod("B", "Z", emptyList()), true)  // Local Music
            gc.returnBoolean(gc.requireMethod("i", "Z", emptyList()), true)  // isIndia (required for Web Series catalog)
            gc.returnBoolean(gc.requireMethod("F", "Z", emptyList()), true)  // onlineOpen (Web Series & Movies tab)
            gc.returnBoolean(gc.requireMethod("D", "Z", emptyList()), true)  // ottDownloadOpen (offline episode downloads)

            val disabledGates = listOf(
                "o", // isSouthAsiaExIndia
                "y", // liveOpen (Live TV)
                "A", // mxtubeOpen (MX Tube)
                "w", // gameOpen (MX Games)
                "v", // fatafatOpen (Shorts / Fatafat)
                "E", // upcomingOpen
                "t", // bazaarOpen
                "u", // downloadOpen (social video saver promo)
                "x", // secondary online tab gate
                "f", // AmazonGameXConfig.enable
                "l", // isOpenSearchYoutubeTab
            )
            for (gateName in disabledGates) {
                gc.returnBoolean(gc.requireMethod(gateName, "Z", emptyList()), false)
            }
        }

        // =========================================================================
        // 4. Instant Splash, Home Shortcut Strip & Banner Ad Removal,
        //    Me-Page Promo Removal & Classic Themes Offline/Online Reliability
        // =========================================================================
        run {
            // Bypass UMP splash stall & silence GdprLog timers.
            val nationalUtilClass = NationalUtilGdprRegionFingerprint.classDef
            nationalUtilClass.returnBoolean(NationalUtilGdprRegionFingerprint.method, false)

            val gdprLogClass = GdprLogClinitFingerprint.classDef
            gdprLogClass.methods.toList().forEach { method ->
                if (AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.returnType == "V" &&
                    method.name != "<clinit>"
                ) {
                    gdprLogClass.returnVoid(method)
                }
            }

            // Remove "MX For You" OTT virtual card from local folder list.
            val mediaListClass = MediaListForYouEntryFingerprint.classDef
            mediaListClass.returnBoolean(MediaListForYouEntryFingerprint.method, false)

            // Remove Home ("Folders") horizontal shortcut strip (`Music | File Transfer | Status Saver |
            // My Playlists | Cleaner | Privacy` + pager dots):
            // 1. Disarm all `()V` tile-fetch/push methods (`c`, `e`, `h`, `i`) on `LocalTilesManager`.
            // 2. Disarm `AdMediaListFragment`'s tile-request `(Z)V` (`Sa`/`Pa`), empty-view tile-header
            //    inflater `(TileResources, TileResources)V` (`La`/`Ia`), and RecyclerView `VIEW_TYPE = 20`
            //    shortcut-strip header injector `(List)Z` (`u9`/`r9`).
            val tilesManagerClass = LocalTilesManagerClinitFingerprint.classDef
            tilesManagerClass.methods
                .filter {
                    it.parameterTypes.isEmpty() &&
                        it.returnType == "V" &&
                        it.name != "<init>" &&
                        it.name != "<clinit>"
                }
                .toList()
                .forEach(tilesManagerClass::returnVoid)

            // Remove Page 1 Home banner/native ad row (`Ka`/`Ha` with `"topPageOffset"`),
            // floating promo button (`Ra`/`Oa` with `"fab_local"`), and Home shortcut strip hooks.
            val adMediaListClass = AdMediaListAttachFabFingerprint.classDef
            adMediaListClass.returnVoid(AdMediaListAttachFabFingerprint.method)
            val insertNativeAdRows = adMediaListClass.methods.firstOrNull {
                it.returnType == "V" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == "Ljava/util/List;" &&
                    it.containsString("topPageOffset")
            } ?: throw PatchException("AdMediaListFragment native/banner ad insertion method not found")
            adMediaListClass.returnVoid(insertNativeAdRows)

            val requestTilesMethod = adMediaListClass.methods.firstOrNull { candidate ->
                candidate.returnType == "V" &&
                    candidate.parameterTypes == listOf("Z") &&
                    candidate.instructions.any { insn ->
                        val fRef = (insn as? ReferenceInstruction)?.reference as? FieldReference
                        fRef?.type == LOCAL_TILES_MANAGER_CLASS
                    }
            } ?: throw PatchException("AdMediaListFragment requestLocalTiles (Z)V method not found")
            adMediaListClass.returnVoid(requestTilesMethod)

            val inflateEmptyTileHeader = adMediaListClass.methods.firstOrNull {
                it.returnType == "V" &&
                    it.parameterTypes == listOf(TILE_RESOURCES_CLASS, TILE_RESOURCES_CLASS)
            } ?: throw PatchException("AdMediaListFragment (TileResources, TileResources)V method not found")
            adMediaListClass.returnVoid(inflateEmptyTileHeader)

            val injectTileStripRow = adMediaListClass.methods.firstOrNull { candidate ->
                candidate.returnType == "Z" &&
                    candidate.parameterTypes == listOf("Ljava/util/List;") &&
                    candidate.instructions.any { insn ->
                        val mRef = (insn as? ReferenceInstruction)?.reference as? MethodReference
                        mRef?.definingClass == TILE_RESOURCES_CLASS && mRef.name == "getTiles"
                    }
            } ?: throw PatchException("AdMediaListFragment shortcut-strip (List)Z method not found")
            adMediaListClass.returnBoolean(injectTileStripRow, false)

            // Disable toolbar promo button (`"bar_local"`) in `ActivityMediaList.J0(Menu)V`.
            val baseMediaClass = BaseMediaPrepareMenuFingerprint.classDef
            val barLocalPromo = baseMediaClass.methods.firstOrNull {
                it.returnType == "V" &&
                    it.parameterTypes == listOf("Landroid/view/Menu;") &&
                    it.containsString("bar_local")
            } ?: throw PatchException("ActivityMediaList bar_local promo method not found")
            baseMediaClass.returnVoid(barLocalPromo)

            // Return null from `PromoteViewModel$Companion.c(String, LifecycleOwner)`.
            val promoteCompanionClass = PromoteViewModelCreateFingerprint.classDef
            promoteCompanionClass.returnNull(PromoteViewModelCreateFingerprint.method)

            // Remove Me-page promotional banner (`"openNavigationPromotion"`) and ad card (`(Z)Z -> false`),
            // and collapse their containers in `onViewCreated` & `onConfigurationChanged`.
            val localMeClass = LocalMeOnViewCreatedFingerprint.classDef
            val mePromoMethod = localMeClass.methods.firstOrNull {
                it.parameterTypes.isEmpty() && it.returnType == "V" && it.containsString("openNavigationPromotion")
            } ?: throw PatchException("LocalMeFragment openNavigationPromotion method not found")
            localMeClass.returnVoid(mePromoMethod)

            val meAdGateMethod = localMeClass.methods.firstOrNull {
                it.parameterTypes == listOf("Z") && it.returnType == "Z"
            } ?: throw PatchException("LocalMeFragment (Z)Z ad gate method not found")
            localMeClass.returnBoolean(meAdGateMethod, false)

            val bindingInfo = resolveLocalMeBinding(localMeClass)
            localMeClass.requireMethod("onViewCreated", "V", listOf("Landroid/view/View;", "Landroid/os/Bundle;"))
                .appendHideBindingViews(bindingInfo, bindingInfo.promoAndAdFields)
            localMeClass.requireMethod("onConfigurationChanged", "V", listOf("Landroid/content/res/Configuration;"))
                .appendHideBindingViews(bindingInfo, bindingInfo.promoAndAdFields)

            // Remove online Zodiac App Themes and wire in-memory 1-tap Classic Themes offline & online.
            installCleanClassicThemeEngine()

            // =========================================================================
            // 6. Cold-Start, Theme-Switch & Runtime Smoothness Optimizations (3.4.1)
            //    (Adjusted to preserve Web Series / OTT Video tab & PlayReady DRM)
            // =========================================================================
            // 6a. `SkinResourcePackHelper.a()V`: runs on every cold start and every theme `recreate()`;
            //     for `external_skin_local_classic_*`, stock fires an HTTP check for `"classic_..."` and
            //     scans/deletes files in the skin directory. Disarming makes theme recreates instant.
            val skinPackHelperClass = SkinResourcePackCheckFingerprint.classDef
            skinPackHelperClass.returnVoid(SkinResourcePackCheckFingerprint.method)

            // 6b. `GdprUtil.a()Z -> true` (`isNotGDPRRegion()`):
            //     Keep `GdprUtil.a()` `true` globally to avoid GDPR splash delays, while leaving
            //     `App.attachBaseContext` intact so `AccountKit` and `Amazon PlayReady DRM` initialize
            //     for Web Series playback.
            val gdprUtilClass = GdprForeignCheckFingerprint.classDef
            gdprUtilClass.returnBoolean(GdprForeignCheckFingerprint.method, true)

            // 6c. `DefaultMultiTypeViewCache` kept active so Web Series / OTT streaming cards render smoothly.

            // 6d. `ANRWatchDog.run()V -> return-void`: skips startup `getHistoricalProcessExitReasons` IPC
            //     and background 3000ms main-thread ping polling.
            val anrWatchDogClass = AnrWatchDogRunFingerprint.classDef
            anrWatchDogClass.returnVoid(AnrWatchDogRunFingerprint.method)

            // 6e. Disarm Amazon Minerva telemetry (`AMXPMetricInitializer.c` + `AMXPMetricEmitter` disabled flag).
            val amxpInitClass = AmxpMetricInitFingerprint.classDef
            amxpInitClass.returnVoid(AmxpMetricInitFingerprint.method)
            val amxpEmitterClass = AmxpMetricEmitterClinitFingerprint.classDef
            AmxpMetricEmitterClinitFingerprint.method.appendBeforeReturnVoid(
                """
                    const/4 v0, 0x1
                    sput-boolean v0, ${amxpEmitterClass.type}->d:Z
                """.trimIndent(),
            )

            // 6f. `FeatureMan2` kept active so remote OTT Video tab & catalog configs load properly.

            // 6g. Disarm `TrackerInitializer`, `TrackingUtil` (`lvj`) static dispatchers, and
            //     `TrackingConst` (`Ldvj;`) lifecycle callbacks while keeping `App.X()` intact so
            //     core MX online session & bottom-bar OTT tab registration runs on startup.
            val trackerInitClass = TrackerInitializerFingerprint.classDef
            trackerInitClass.returnVoid(TrackerInitializerFingerprint.method)
            val trackingUtilClass = TrackingUtilClinitFingerprint.classDef
            trackingUtilClass.methods
                .filter {
                    AccessFlags.STATIC.isSet(it.accessFlags) &&
                        it.returnType == "V" &&
                        it.name != "<clinit>" &&
                        it.name != "a"
                }
                .toList()
                .forEach(trackingUtilClass::returnVoid)

            val trackingLifecycleClass = TrackingLifecycleCallbackFingerprint.classDef
            trackingLifecycleClass.returnVoid(TrackingLifecycleCallbackFingerprint.method)
            trackingLifecycleClass.returnVoid(
                trackingLifecycleClass.requireMethod("onActivityResumed", "V", listOf("Landroid/app/Activity;")),
            )

            // 6h. Disarm `App.t()` background bloat (`InAppPush` 5-second repeating timer, `ComScore`
            //     `Analytics.start` / UX pings, and `CleverTap` `ActivityLifecycleCallback`) while
            //     keeping `GetGroupDetailsWithCache`, Network Stream (`jxk.a`), and Chromecast intact!
            val inAppPushClass = InAppPushRunFingerprint.classDef
            inAppPushClass.returnVoid(InAppPushRunFingerprint.method)
            inAppPushClass.returnVoid(inAppPushClass.requireMethod("c", "V", emptyList()))

            val comScoreClass = ComScoreClinitFingerprint.classDef
            ComScoreClinitFingerprint.method.appendBeforeReturnVoid(
                """
                    const/4 v0, 0x1
                    sput-boolean v0, ${comScoreClass.type}->c:Z
                """.trimIndent(),
            )
            comScoreClass.returnVoid(comScoreClass.requireMethod("a", "V", listOf("Z")))
            comScoreClass.returnVoid(comScoreClass.requireMethod("b", "V", emptyList()))

            val cleverTapLifecycleClass = CleverTapLifecycleCallbackFingerprint.classDef
            cleverTapLifecycleClass.returnVoid(CleverTapLifecycleCallbackFingerprint.method)
            cleverTapLifecycleClass.returnVoid(
                cleverTapLifecycleClass.requireMethod("onActivityResumed", "V", listOf("Landroid/app/Activity;")),
            )
            cleverTapLifecycleClass.returnVoid(
                cleverTapLifecycleClass.requireMethod("onActivityPaused", "V", listOf("Landroid/app/Activity;")),
            )
            val cleverTapOuterType = cleverTapLifecycleClass.type.substringBefore('$') + ";"
            val cleverTapOuterClass = mutableClassDefBy(cleverTapOuterType)
            cleverTapOuterClass.methods.firstOrNull { it.name == "<clinit>" && it.parameterTypes.isEmpty() }
                ?.appendBeforeReturnVoid(
                    """
                        const/4 v0, 0x1
                        sput-boolean v0, $cleverTapOuterType->a:Z
                    """.trimIndent(),
                )

            // 6i. Disarm `AdInitializer` (`Llq;->a()V`) callback invoked from `ActivityWelcomeMX.onCreate`
            //     so cold launches and theme recreates never register `hif.b` or spawn `gd4.h` ad workers.
            val adInitCallbackClass = AdInitializerCallbackFingerprint.classDef
            adInitCallbackClass.returnVoid(AdInitializerCallbackFingerprint.method)

            // Promote inner classes of stripped outer classes to PUBLIC so BytecodeMode.STRIP_SAFE
            // never triggers cross-DEX package-private IllegalAccessError even on Android 7.0–7.1 (API 24–25).
            val innerPrefixesToPromote = listOf(
                "Lcom/mxtech/videoplayer/ad/ActivityScreen\$",
                "Lcom/mxtech/videoplayer/ad/App\$",
                "Lcom/mxtech/videoplayer/ad/online/abtest/LocalOnlyExitAdTest\$",
                "Lcom/mxtech/videoplayer/ad/online/ad/appopen/AppOpenAdManager\$",
                "Lcom/mxtech/videoplayer/ad/online/ad/appopen/AppOpenAdRequestManager\$validateAdUrl\$1;",
                "Lcom/mxtech/videoplayer/ad/online/theme/SkinResourcePackHelper\$",
                "Lcom/mxtech/videoplayer/ad/online/theme/ui/ThemeListViewModel\$",
                "Lcom/mxtech/videoplayer/ad/push/a\$",
                "Lcom/mxtech/videoplayer/ad/subscriptions/ui/GetGroupDetailsWithCache\$",
                "${anrWatchDogClass.type.dropLast(1)}\$",
                "${trackingUtilClass.type.dropLast(1)}\$",
                "${comScoreClass.type.dropLast(1)}\$",
            )
            classDefForEach { classDef ->
                if (!AccessFlags.PUBLIC.isSet(classDef.accessFlags) &&
                    innerPrefixesToPromote.any(classDef.type::startsWith)
                ) {
                    mutableClassDefBy(classDef).let {
                        it.accessFlags = (it.accessFlags and AccessFlags.PRIVATE.value.inv() and AccessFlags.PROTECTED.value.inv()) or AccessFlags.PUBLIC.value
                    }
                }
            }
        }
    }
}

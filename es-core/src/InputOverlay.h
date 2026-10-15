//  SPDX-License-Identifier: MIT
//
//  PlayRetro
//  InputOverlay.h
//
//  Touch gesture input for mobile devices. Instead of drawing virtual controller buttons,
//  finger gestures are translated into the same navigation inputs a controller produces:
//
//    tap                     A (select / confirm)
//    drag or swipe           D-pad steps in the drag direction (natural scrolling), with
//                            momentum after a fast swipe
//    long press              Back button (context / options menu)
//    two-finger tap          B (go back)
//    three-finger tap        Start (main menu)
//    two-finger swipe        Shoulder buttons (left/right) or triggers (up/down) to jump
//                            through long lists
//
//  The Android back gesture is handled by InputManager and mapped to B.
//

#ifndef ES_CORE_INPUT_OVERLAY_H
#define ES_CORE_INPUT_OVERLAY_H

#include "utils/MathUtil.h"

#include <SDL2/SDL_events.h>

#include <map>
#include <utility>
#include <vector>

class InputOverlay
{
public:
    // Button IDs outside the SDL_GameControllerButton range for the analog triggers.
    enum TriggerButtons {
        TRIGGER_LEFT = 1024,
        TRIGGER_RIGHT = 1025
    };

    // A button event to forward to the window: (button ID, 1 = pressed / 0 = released).
    using ButtonEvent = std::pair<int, int>;

    static InputOverlay& getInstance();

    void init();
    // Processes a finger event and returns the resulting button events, which may be empty.
    // Returns false if the event wasn't consumed.
    bool processFingerEvent(const SDL_TouchFingerEvent& event, std::vector<ButtonEvent>& output);
    // Drives time-based gestures (long press, momentum). Resulting button events are sent
    // directly to the window.
    void update(int deltaTime);
    void render(const glm::mat4& parentTrans);

    // Called when menus or dialogs change state; cancels any gesture in progress.
    void unselectAllButtons() { reset(); }
    // Kept for API compatibility with the settings menu; gestures have no visual buttons.
    void createButtons() {}
    void clearButtons() { reset(); }
    void resetFadeTimer() {}

private:
    InputOverlay() noexcept;

    struct Finger {
        glm::vec2 start;
        glm::vec2 position;
        glm::vec2 lastStep;
        unsigned int startTime;
        unsigned int lastMotionTime;
        glm::vec2 velocity;
    };

    void reset();
    void finishGesture(std::vector<ButtonEvent>& output);
    static void pressAndRelease(int button, std::vector<ButtonEvent>& output);
    void emitSteps(Finger& finger, std::vector<ButtonEvent>& output);
    void sendToWindow(const std::vector<ButtonEvent>& events);

    std::map<SDL_FingerID, Finger> mFingers;
    int mMaxFingers;
    bool mDragging;
    bool mLongPressFired;
    bool mMultiFingerSwipe;

    // Momentum scrolling after a fast swipe.
    glm::vec2 mMomentum;
    float mMomentumAccumulator;
    int mMomentumButton;

    float mAspectRatio;
};

#endif // ES_CORE_INPUT_OVERLAY_H

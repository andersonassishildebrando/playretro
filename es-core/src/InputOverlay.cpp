//  SPDX-License-Identifier: MIT
//
//  PlayRetro
//  InputOverlay.cpp
//
//  Touch gesture input for mobile devices, see InputOverlay.h.
//

#include "InputOverlay.h"

#include "InputConfig.h"
#include "InputManager.h"
#include "Window.h"
#include "renderers/Renderer.h"

#include <SDL2/SDL_gamecontroller.h>
#include <SDL2/SDL_timer.h>

#include <cmath>

namespace
{
    // Distances are in units of the screen height, times are in milliseconds.
    constexpr float TAP_MAX_MOVE {0.03f};
    constexpr unsigned int TAP_MAX_TIME {350};
    constexpr unsigned int LONG_PRESS_TIME {550};
    constexpr float DRAG_THRESHOLD {0.035f};
    constexpr float STEP_DISTANCE {0.075f};
    constexpr float MULTI_SWIPE_DISTANCE {0.12f};

    // Momentum after a fast swipe, in steps per second.
    constexpr float MOMENTUM_MIN_VELOCITY {1.4f};
    constexpr float MOMENTUM_STOP_RATE {2.5f};
    constexpr float MOMENTUM_MAX_RATE {28.0f};
    constexpr float MOMENTUM_FRICTION {3.2f};
} // namespace

InputOverlay::InputOverlay() noexcept
    : mMaxFingers {0}
    , mDragging {false}
    , mLongPressFired {false}
    , mMultiFingerSwipe {false}
    , mMomentum {0.0f, 0.0f}
    , mMomentumAccumulator {0.0f}
    , mMomentumButton {-1}
    , mAspectRatio {1.0f}
{
}

InputOverlay& InputOverlay::getInstance()
{
    static InputOverlay instance;
    return instance;
}

void InputOverlay::init()
{
    const float width {Renderer::getScreenWidth()};
    const float height {Renderer::getScreenHeight()};
    mAspectRatio = (height > 0.0f ? width / height : 1.0f);
    reset();
}

void InputOverlay::reset()
{
    mFingers.clear();
    mMaxFingers = 0;
    mDragging = false;
    mLongPressFired = false;
    mMultiFingerSwipe = false;
    mMomentum = {0.0f, 0.0f};
    mMomentumAccumulator = 0.0f;
    mMomentumButton = -1;
}

void InputOverlay::pressAndRelease(int button, std::vector<ButtonEvent>& output)
{
    output.emplace_back(button, 1);
    output.emplace_back(button, 0);
}

bool InputOverlay::processFingerEvent(const SDL_TouchFingerEvent& event,
                                      std::vector<ButtonEvent>& output)
{
    // Normalized coordinates are converted so that both axes use screen height units.
    const glm::vec2 position {event.x * mAspectRatio, event.y};
    const unsigned int now {SDL_GetTicks()};

    switch (event.type) {
        case SDL_FINGERDOWN: {
            // Touching the screen stops any momentum scrolling, like on native lists.
            mMomentum = {0.0f, 0.0f};
            mMomentumButton = -1;
            if (mFingers.empty()) {
                mMaxFingers = 0;
                mDragging = false;
                mLongPressFired = false;
                mMultiFingerSwipe = false;
            }
            mFingers[event.fingerId] = {position, position, position, now, now, {0.0f, 0.0f}};
            mMaxFingers = std::max(mMaxFingers, static_cast<int>(mFingers.size()));
            return true;
        }
        case SDL_FINGERMOTION: {
            auto it = mFingers.find(event.fingerId);
            if (it == mFingers.end())
                return false;
            Finger& finger {it->second};
            const unsigned int elapsed {std::max(1u, now - finger.lastMotionTime)};
            const glm::vec2 velocity {(position - finger.position) * (1000.0f / elapsed)};
            // Smooth the velocity to avoid spikes from uneven event timing.
            finger.velocity = finger.velocity * 0.4f + velocity * 0.6f;
            finger.position = position;
            finger.lastMotionTime = now;

            if (mMaxFingers == 1 && !mLongPressFired) {
                if (!mDragging && glm::length(position - finger.start) > DRAG_THRESHOLD)
                    mDragging = true;
                if (mDragging)
                    emitSteps(finger, output);
            }
            return true;
        }
        case SDL_FINGERUP: {
            auto it = mFingers.find(event.fingerId);
            if (it == mFingers.end())
                return false;
            it->second.position = position;

            if (mFingers.size() == 1)
                finishGesture(output);
            else if (mMaxFingers >= 2 && !mMultiFingerSwipe) {
                const glm::vec2 delta {position - it->second.start};
                if (glm::length(delta) > MULTI_SWIPE_DISTANCE) {
                    mMultiFingerSwipe = true;
                    int button;
                    if (std::fabs(delta.x) > std::fabs(delta.y))
                        button = (delta.x < 0.0f ? SDL_CONTROLLER_BUTTON_RIGHTSHOULDER :
                                                   SDL_CONTROLLER_BUTTON_LEFTSHOULDER);
                    else
                        button = (delta.y < 0.0f ? TRIGGER_RIGHT : TRIGGER_LEFT);
                    pressAndRelease(button, output);
                }
            }
            mFingers.erase(it);
            return true;
        }
        default: {
            return false;
        }
    }
}

void InputOverlay::emitSteps(Finger& finger, std::vector<ButtonEvent>& output)
{
    // Lock to the dominant axis of the drag so lists don't jitter sideways.
    const glm::vec2 total {finger.position - finger.start};
    const bool horizontal {std::fabs(total.x) > std::fabs(total.y)};
    auto pending = [&] {
        return horizontal ? finger.position.x - finger.lastStep.x :
                            finger.position.y - finger.lastStep.y;
    };

    // Natural scrolling: moving the finger up advances down the list and moving it left
    // advances to the right.
    while (std::fabs(pending()) >= STEP_DISTANCE) {
        const float direction {pending() < 0.0f ? -1.0f : 1.0f};
        int button;
        if (horizontal)
            button = (direction < 0.0f ? SDL_CONTROLLER_BUTTON_DPAD_RIGHT :
                                         SDL_CONTROLLER_BUTTON_DPAD_LEFT);
        else
            button = (direction < 0.0f ? SDL_CONTROLLER_BUTTON_DPAD_DOWN :
                                         SDL_CONTROLLER_BUTTON_DPAD_UP);
        pressAndRelease(button, output);
        if (horizontal)
            finger.lastStep.x += direction * STEP_DISTANCE;
        else
            finger.lastStep.y += direction * STEP_DISTANCE;
    }
}

void InputOverlay::finishGesture(std::vector<ButtonEvent>& output)
{
    const Finger& finger {mFingers.begin()->second};
    const unsigned int duration {SDL_GetTicks() - finger.startTime};
    const float moved {glm::length(finger.position - finger.start)};

    if (mLongPressFired || mMultiFingerSwipe)
        return;

    if (mDragging) {
        // Continue scrolling with momentum after a fast swipe.
        const glm::vec2 total {finger.position - finger.start};
        const bool horizontal {std::fabs(total.x) > std::fabs(total.y)};
        const float speed {horizontal ? finger.velocity.x : finger.velocity.y};
        if (SDL_GetTicks() - finger.lastMotionTime < 80 &&
            std::fabs(speed) > MOMENTUM_MIN_VELOCITY) {
            if (horizontal)
                mMomentumButton = (speed < 0.0f ? SDL_CONTROLLER_BUTTON_DPAD_RIGHT :
                                                  SDL_CONTROLLER_BUTTON_DPAD_LEFT);
            else
                mMomentumButton = (speed < 0.0f ? SDL_CONTROLLER_BUTTON_DPAD_DOWN :
                                                  SDL_CONTROLLER_BUTTON_DPAD_UP);
            mMomentum = {std::min(std::fabs(speed) / STEP_DISTANCE, MOMENTUM_MAX_RATE), 0.0f};
            mMomentumAccumulator = 0.0f;
        }
        return;
    }

    if (duration <= TAP_MAX_TIME + 150 && moved <= TAP_MAX_MOVE * mMaxFingers) {
        if (mMaxFingers == 1)
            pressAndRelease(SDL_CONTROLLER_BUTTON_A, output);
        else if (mMaxFingers == 2)
            pressAndRelease(SDL_CONTROLLER_BUTTON_B, output);
        else
            pressAndRelease(SDL_CONTROLLER_BUTTON_START, output);
    }
}

void InputOverlay::update(int deltaTime)
{
    std::vector<ButtonEvent> events;

    if (mFingers.size() == 1 && mMaxFingers == 1 && !mDragging && !mLongPressFired) {
        const Finger& finger {mFingers.begin()->second};
        if (SDL_GetTicks() - finger.startTime >= LONG_PRESS_TIME &&
            glm::length(finger.position - finger.start) <= TAP_MAX_MOVE) {
            mLongPressFired = true;
            pressAndRelease(SDL_CONTROLLER_BUTTON_BACK, events);
        }
    }

    if (mMomentumButton != -1 && mFingers.empty()) {
        const float seconds {static_cast<float>(deltaTime) / 1000.0f};
        mMomentumAccumulator += mMomentum.x * seconds;
        while (mMomentumAccumulator >= 1.0f) {
            pressAndRelease(mMomentumButton, events);
            mMomentumAccumulator -= 1.0f;
        }
        mMomentum.x *= std::exp(-MOMENTUM_FRICTION * seconds);
        if (mMomentum.x < MOMENTUM_STOP_RATE)
            mMomentumButton = -1;
    }

    if (!events.empty())
        sendToWindow(events);
}

void InputOverlay::sendToWindow(const std::vector<ButtonEvent>& events)
{
    InputConfig* config {InputManager::getInstance().getInputConfigByDevice(DEVICE_TOUCH)};
    for (auto& [button, value] : events)
        Window::getInstance()->input(config, Input(DEVICE_TOUCH, TYPE_TOUCH, button, value, false));
}

void InputOverlay::render(const glm::mat4& parentTrans)
{
    // Gestures have no persistent visuals.
    (void)parentTrans;
}

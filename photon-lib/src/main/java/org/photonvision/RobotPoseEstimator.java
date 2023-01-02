/*
 * MIT License
 *
 * Copyright (c) 2022 PhotonVision
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.photonvision;

import edu.wpi.first.apriltag.AprilTagFieldLayout;
import edu.wpi.first.math.Pair;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Rotation3d;
import edu.wpi.first.math.geometry.Transform3d;
import edu.wpi.first.math.geometry.Translation3d;
import edu.wpi.first.wpilibj.DriverStation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.photonvision.targeting.PhotonPipelineResult;
import org.photonvision.targeting.PhotonTrackedTarget;

public class RobotPoseEstimator {
    /**
     *
     *
     * <ul>
     *   <li><strong>LOWEST_AMBIGUITY</strong>: Choose the Pose with the lowest ambiguity
     *   <li><strong>CLOSEST_TO_CAMERA_HEIGHT</strong>: Choose the Pose which is closest to the camera
     *       height
     *   <li><strong>CLOSEST_TO_REFERENCE_POSE</strong>: Choose the Pose which is closest to the pose
     *       from setReferencePose()
     *   <li><strong>CLOSEST_TO_LAST_POSE</strong>: Choose the Pose which is closest to the last pose
     *       calculated
     * </ul>
     */
    public enum PoseStrategy {
        LOWEST_AMBIGUITY,
        CLOSEST_TO_CAMERA_HEIGHT,
        CLOSEST_TO_REFERENCE_POSE,
        CLOSEST_TO_LAST_POSE,
        AVERAGE_BEST_TARGETS
    }

    private AprilTagFieldLayout aprilTags;
    private PoseStrategy strategy;
    private ArrayList<Pair<PhotonCamera, Transform3d>> cameras;
    private Pose3d lastPose;

    private Pose3d referencePose;
    private HashSet<Integer> reportedErrors;

    /**
     * Create a new RobotPoseEstimator.
     *
     * <p>Example: {@code <code> <p> Map<Integer, Pose3d> map = new HashMap<>(); <p> map.put(1, new
     * Pose3d(1.0, 2.0, 3.0, new Rotation3d())); // Tag ID 1 is at (1.0,2.0,3.0) </code> }
     *
     * @param aprilTags A AprilTagFieldLayout linking AprilTag IDs to Pose3ds with respect to the
     *     FIRST field.
     * @param strategy The strategy it should use to determine the best pose.
     * @param cameras An ArrayList of Pairs of PhotonCameras and their respective Transform3ds from
     *     the center of the robot to the cameras.
     */
    public RobotPoseEstimator(
            AprilTagFieldLayout aprilTags,
            PoseStrategy strategy,
            ArrayList<Pair<PhotonCamera, Transform3d>> cameras) {
        this.aprilTags = aprilTags;
        this.strategy = strategy;
        this.cameras = cameras;
        lastPose = new Pose3d();
        reportedErrors = new HashSet<>();
    }

    /**
     * Update the estimated pose using the selected strategy.
     *
     * @return The updated estimated pose and the latency in milliseconds Estimated pose may be null
     *     if no targets were seen
     */
    public Pair<Pose3d, Double> update() {
        if (cameras.isEmpty()) {
            DriverStation.reportError("[RobotPoseEstimator] Missing any camera!", false);
            return Pair.of(lastPose, 0.);
        }
        Pair<Pose3d, Double> pair;
        switch (strategy) {
            case LOWEST_AMBIGUITY:
                pair = lowestAmbiguityStrategy();
                lastPose = pair.getFirst();
                return pair;
            case CLOSEST_TO_CAMERA_HEIGHT:
                pair = closestToCameraHeightStrategy();
                lastPose = pair.getFirst();
                return pair;
            case CLOSEST_TO_REFERENCE_POSE:
                pair = closestToReferencePoseStrategy();
                lastPose = pair.getFirst();
                return pair;
            case CLOSEST_TO_LAST_POSE:
                referencePose = lastPose;
                pair = closestToReferencePoseStrategy();
                lastPose = pair.getFirst();
                return pair;
            case AVERAGE_BEST_TARGETS:
                pair = averageBestTargetsStrategy();
                lastPose = pair.getFirst();
                return pair;
            default:
                DriverStation.reportError("[RobotPoseEstimator] Invalid pose strategy!", false);
                return Pair.of(null, 0.);
        }
    }

    private Pair<Pose3d, Double> lowestAmbiguityStrategy() {
        int lowestAI = -1;
        int lowestAJ = -1;
        double lowestAmbiguityScore = 10;
        ArrayList<PhotonPipelineResult> results = new ArrayList<PhotonPipelineResult>(cameras.size());

        // Sample result from each camera
        for (int i = 0; i < cameras.size(); i++) {
            Pair<PhotonCamera, Transform3d> p = cameras.get(i);
            results.add(p.getFirst().getLatestResult());
        }

        // Loop over each ambiguity of all the cameras
        for (int i = 0; i < cameras.size(); i++) {
            List<PhotonTrackedTarget> targets = results.get(i).targets;
            for (int j = 0; j < targets.size(); j++) {
                if (targets.get(j).getPoseAmbiguity() < lowestAmbiguityScore) {
                    lowestAI = i;
                    lowestAJ = j;
                    lowestAmbiguityScore = targets.get(j).getPoseAmbiguity();
                }
            }
        }

        // No targets, return null
        if (lowestAI == -1 || lowestAJ == -1) {
            return Pair.of(null, 0.);
        }

        // Pick the lowest and do the heavy calculations
        PhotonTrackedTarget bestTarget = results.get(lowestAI).targets.get(lowestAJ);

        // If the map doesn't contain the ID fail
        var tmp = aprilTags.getTagPose(bestTarget.getFiducialId());
        if (tmp.isEmpty()) {
            if (!reportedErrors.contains(bestTarget.getFiducialId())) {
                DriverStation.reportError(
                        "[RobotPoseEstimator] Tried to get pose of unknown April Tag: "
                                + bestTarget.getFiducialId(),
                        false);
                reportedErrors.add(bestTarget.getFiducialId());
            }
            return Pair.of(lastPose, 0.);
        }

        var tagPose = tmp.get();

        return Pair.of(
                tagPose
                        .transformBy(bestTarget.getBestCameraToTarget().inverse())
                        .transformBy(cameras.get(lowestAI).getSecond().inverse()),
                results.get(lowestAI).getLatencyMillis());
    }

    private Pair<Pose3d, Double> closestToCameraHeightStrategy() {
        double smallestHeightDifference = 10e9;
        double mili = 0;
        Pose3d pose = null;

        for (int i = 0; i < cameras.size(); i++) {
            Pair<PhotonCamera, Transform3d> p = cameras.get(i);
            var result = p.getFirst().getLatestResult();
            List<PhotonTrackedTarget> targets = result.targets;
            for (int j = 0; j < targets.size(); j++) {
                PhotonTrackedTarget target = targets.get(j);
                var tmp = aprilTags.getTagPose(target.getFiducialId());
                if (tmp.isEmpty()) {
                    if (!reportedErrors.contains(target.getFiducialId())) {
                        DriverStation.reportWarning(
                                "[RobotPoseEstimator] Tried to get pose of unknown April Tag: "
                                        + target.getFiducialId(),
                                false);
                        reportedErrors.add(target.getFiducialId());
                    }
                    continue;
                }
                Pose3d targetPose = tmp.get();
                double alternativeDifference =
                        Math.abs(
                                p.getSecond().getZ()
                                        - targetPose.transformBy(target.getAlternateCameraToTarget().inverse()).getZ());
                double bestDifference =
                        Math.abs(
                                p.getSecond().getZ()
                                        - targetPose.transformBy(target.getBestCameraToTarget().inverse()).getZ());
                if (alternativeDifference < smallestHeightDifference) {
                    smallestHeightDifference = alternativeDifference;
                    pose =
                            targetPose
                                    .transformBy(target.getAlternateCameraToTarget().inverse())
                                    .transformBy(p.getSecond().inverse());
                    mili = result.getLatencyMillis();
                }
                if (bestDifference < smallestHeightDifference) {
                    smallestHeightDifference = bestDifference;
                    pose =
                            targetPose
                                    .transformBy(target.getBestCameraToTarget().inverse())
                                    .transformBy(p.getSecond().inverse());
                    mili = result.getLatencyMillis();
                }
            }
        }

        return Pair.of(pose, mili);
    }

    private Pair<Pose3d, Double> closestToReferencePoseStrategy() {
        if (referencePose == null) {
            DriverStation.reportError(
                    "[RobotPoseEstimator] Tried to use reference pose strategy without setting the reference!",
                    false);
            return Pair.of(null, 0.);
        }
        double smallestDifference = 10e9;
        double mili = 0;
        Pose3d pose = null;
        for (int i = 0; i < cameras.size(); i++) {
            Pair<PhotonCamera, Transform3d> p = cameras.get(i);
            var result = p.getFirst().getLatestResult();
            List<PhotonTrackedTarget> targets = result.targets;
            for (int j = 0; j < targets.size(); j++) {
                PhotonTrackedTarget target = targets.get(j);
                // If the map doesn't contain the ID fail
                var tmp = aprilTags.getTagPose(target.getFiducialId());
                if (tmp.isEmpty()) {
                    if (!reportedErrors.contains(target.getFiducialId())) {
                        DriverStation.reportWarning(
                                "[RobotPoseEstimator] Tried to get pose of unknown April Tag: "
                                        + target.getFiducialId(),
                                false);
                        reportedErrors.add(target.getFiducialId());
                    }
                    continue;
                }
                Pose3d targetPose = tmp.get();
                Pose3d botBestPose =
                        targetPose
                                .transformBy(target.getAlternateCameraToTarget().inverse())
                                .transformBy(p.getSecond().inverse());
                Pose3d botAltPose =
                        targetPose
                                .transformBy(target.getBestCameraToTarget().inverse())
                                .transformBy(p.getSecond().inverse());
                double alternativeDifference = Math.abs(calculateDifference(referencePose, botAltPose));
                double bestDifference = Math.abs(calculateDifference(referencePose, botBestPose));
                if (alternativeDifference < smallestDifference) {
                    smallestDifference = alternativeDifference;
                    pose = botAltPose;
                    mili = result.getLatencyMillis();
                }
                if (bestDifference < smallestDifference) {
                    smallestDifference = bestDifference;
                    pose = botBestPose;
                    mili = result.getLatencyMillis();
                }
            }
        }
        return Pair.of(pose, mili);
    }

    /** Return the average of the best target poses using ambiguity as weight */
    private Pair<Pose3d, Double> averageBestTargetsStrategy() {
        //                  Pair of Double, Double = Ambiguity, Mili
        List<Pair<Pose3d, Pair<Double, Double>>> tempPoses = new ArrayList<>();
        double totalAmbiguity = 0;
        for (int i = 0; i < cameras.size(); i++) {
            Pair<PhotonCamera, Transform3d> p = cameras.get(i);
            var result = p.getFirst().getLatestResult();
            List<PhotonTrackedTarget> targets = result.targets;
            for (int j = 0; j < targets.size(); j++) {
                PhotonTrackedTarget target = targets.get(j);
                // If the map doesn't contain the ID fail
                var tmp = aprilTags.getTagPose(target.getFiducialId());
                if (tmp.isEmpty()) {
                    if (!reportedErrors.contains(target.getFiducialId())) {
                        DriverStation.reportWarning(
                                "[RobotPoseEstimator] Tried to get pose of unknown April Tag: "
                                        + target.getFiducialId(),
                                false);
                        reportedErrors.add(target.getFiducialId());
                    }
                    continue;
                }
                Pose3d targetPose = tmp.get();
                try {
                    totalAmbiguity += 1. / target.getPoseAmbiguity();
                } catch (ArithmeticException e) {
                    // A total ambiguity of zero exists, using that pose instead!",
                    return Pair.of(
                            targetPose
                                    .transformBy(target.getBestCameraToTarget().inverse())
                                    .transformBy(p.getSecond().inverse()),
                            result.getLatencyMillis());
                }
                tempPoses.add(
                        Pair.of(
                                targetPose
                                        .transformBy(target.getBestCameraToTarget().inverse())
                                        .transformBy(p.getSecond().inverse()),
                                Pair.of(target.getPoseAmbiguity(), result.getLatencyMillis())));
            }
        }

        Translation3d transform = new Translation3d();
        Rotation3d rotation = new Rotation3d();
        double latency = 0;

        if (tempPoses.size() > 0) {
            for (Pair<Pose3d, Pair<Double, Double>> pair : tempPoses) {
                try {
                    double weight = (1. / pair.getSecond().getFirst()) / totalAmbiguity;
                    transform = transform.plus(pair.getFirst().getTranslation().times(weight));
                    rotation = rotation.plus(pair.getFirst().getRotation().times(weight));
                    latency +=
                            pair.getSecond().getSecond() * weight; // NOTE: Average latency may not work well
                } catch (ArithmeticException e) {
                    DriverStation.reportWarning(
                            "[RobotPoseEstimator] A total ambiguity of zero exists, using that pose instead!",
                            false);
                    return Pair.of(pair.getFirst(), pair.getSecond().getSecond());
                }
            }
            return Pair.of(new Pose3d(transform, rotation), latency);
        } else {
            return new Pair<Pose3d, Double>(null, -1.0);
        }
    }

    /**
     * Difference is defined as the vector magnitude between the two poses
     *
     * @return The absolute "difference" (>=0) between two Pose3ds.
     */
    private double calculateDifference(Pose3d x, Pose3d y) {
        return x.getTranslation().getDistance(y.getTranslation());
    }

    /** @param aprilTags the aprilTags to set */
    public void setAprilTags(AprilTagFieldLayout aprilTags) {
        this.aprilTags = aprilTags;
    }

    /** @return the aprilTags */
    public AprilTagFieldLayout getAprilTags() {
        return aprilTags;
    }

    /** @return the strategy */
    public PoseStrategy getStrategy() {
        return strategy;
    }

    /** @param strategy the strategy to set */
    public void setStrategy(PoseStrategy strategy) {
        this.strategy = strategy;
    }

    /** @return the referencePose */
    public Pose3d getReferencePose() {
        return referencePose;
    }

    /**
     * Update the stored reference pose for use with CLOSEST_TO_REFERENCE_POSE
     *
     * @param referencePose the referencePose to set
     */
    public void setReferencePose(Pose3d referencePose) {
        this.referencePose = referencePose;
    }

    /**
     * Update the stored reference pose for use with CLOSEST_TO_REFERENCE_POSE
     *
     * @param referencePose the referencePose to set
     */
    public void setReferencePose(Pose2d referencePose) {
        this.referencePose = new Pose3d(referencePose);
    }

    /**
     * UPdate the stored last pose. Useful for setting the initial estimate with CLOSEST_TO_LAST_POSE
     *
     * @param lastPose the lastPose to set
     */
    public void setLastPose(Pose3d lastPose) {
        this.lastPose = lastPose;
    }
}
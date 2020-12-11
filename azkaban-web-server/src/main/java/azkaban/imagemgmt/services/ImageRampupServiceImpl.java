/*
 * Copyright 2020 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package azkaban.imagemgmt.services;

import static azkaban.Constants.ImageMgmtConstants.IMAGE_TYPE;

import azkaban.imagemgmt.daos.ImageRampupDao;
import azkaban.imagemgmt.dto.ImageMetadataRequest;
import azkaban.imagemgmt.exeception.ImageMgmtException;
import azkaban.imagemgmt.exeception.ImageMgmtValidationException;
import azkaban.imagemgmt.models.ImageRampup.StabilityTag;
import azkaban.imagemgmt.models.ImageRampupPlan;
import azkaban.imagemgmt.models.ImageRampupPlanRequest;
import azkaban.imagemgmt.models.ImageRampupRequest;
import azkaban.imagemgmt.utils.ConverterUtils;
import azkaban.imagemgmt.utils.ValidatorUtils;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This service layer implementation for processing and delegation of image rampup APIs. For example
 * API request processing and validation are handled in this layer. Eventually the requests are
 * routed to the DAO layer for data access.
 */
@Singleton
public class ImageRampupServiceImpl implements ImageRampupService {

  private static final Logger log = LoggerFactory.getLogger(ImageRampupServiceImpl.class);

  private final ImageRampupDao imageRampupDao;
  private final ConverterUtils converterUtils;

  @Inject
  public ImageRampupServiceImpl(ImageRampupDao imageRampupDao,
      ConverterUtils converterUtils) {
    this.imageRampupDao = imageRampupDao;
    this.converterUtils = converterUtils;
  }

  @Override
  public int createImageRampupPlan(ImageMetadataRequest imageMetadataRequest)
      throws IOException, ImageMgmtException {
    // Convert input json payload to ImageRampupPlanRequest object
    ImageRampupPlanRequest imageRampupPlanRequest = converterUtils
        .convertToModel(imageMetadataRequest.getJsonPayload(),
            ImageRampupPlanRequest.class);
    // set the user who invoked the API
    imageRampupPlanRequest.setCreatedBy(imageMetadataRequest.getUser());
    imageRampupPlanRequest.setModifiedBy(imageMetadataRequest.getUser());
    for (ImageRampupRequest imageRampupRequest : imageRampupPlanRequest.getImageRampups()) {
      imageRampupRequest.setCreatedBy(imageMetadataRequest.getUser());
      imageRampupRequest.setModifiedBy(imageMetadataRequest.getUser());
    }
    // input validation for ImageRampupPlanRequest
    if (!ValidatorUtils.validateObject(imageRampupPlanRequest)) {
      throw new ImageMgmtValidationException("Provide valid input for creating image rampup "
          + "metadata");
    }
    vaidateRampup(imageRampupPlanRequest);
    // Invoke DAO method to create rampup plan and rampup details
    return imageRampupDao.createImageRampupPlan(imageRampupPlanRequest);
  }

  @Override
  public Optional<ImageRampupPlan> getActiveRampupPlan(String imageTypeName)
      throws ImageMgmtException {
    return imageRampupDao.getActiveImageRampupPlan(imageTypeName, true);
  }

  @Override
  public void updateImageRampupPlan(ImageMetadataRequest imageMetadataRequest) throws IOException,
      ImageMgmtException {
    ImageRampupPlanRequest imageRampupPlanRequest = converterUtils
        .convertToModel(imageMetadataRequest.getJsonPayload(),
            ImageRampupPlanRequest.class);
    imageRampupPlanRequest
        .setImageTypeName(String.valueOf(imageMetadataRequest.getParams().get(IMAGE_TYPE)));
    // set the user who invoked the API
    imageRampupPlanRequest.setModifiedBy(imageMetadataRequest.getUser());
    for (ImageRampupRequest imageRampupRequest : imageRampupPlanRequest.getImageRampups()) {
      imageRampupRequest.setModifiedBy(imageMetadataRequest.getUser());
    }
    // input validation for image version create request
    if (!ValidatorUtils.validateObject(imageRampupPlanRequest)) {
      throw new ImageMgmtValidationException("Provide valid input for creating image version "
          + "metadata");
    }
    vaidateRampup(imageRampupPlanRequest);
    imageRampupDao.updateImageRampupPlan(imageRampupPlanRequest);
  }

  /**
   * Validate input provided as part of rampup request. Here are the validations - 1. Total rampup
   * percentage must add upto 100. 2. Rampup details must not have duplicate image versions. 3. If a
   * specific version is marked as UNSTABLE in the rampup, the corresponding rampup percentage must
   * be zero.
   *
   * @param imageRampupPlanRequest
   * @return boolean
   */
  private boolean vaidateRampup(ImageRampupPlanRequest imageRampupPlanRequest) {
    List<ImageRampupRequest> imageRampupRequests = imageRampupPlanRequest.getImageRampups();
    if (imageRampupRequests == null || imageRampupRequests.isEmpty()) {
      throw new ImageMgmtValidationException("Missing rampup details");
    }
    // Check for total rampup percentage
    int totalRampupPercentage = 0;
    for (ImageRampupRequest imageRampupRequest : imageRampupRequests) {
      totalRampupPercentage += imageRampupRequest.getRampupPercentage();
    }
    if (totalRampupPercentage != 100) {
      throw new ImageMgmtValidationException("Total rampup percentage for all the version must be"
          + " 100");
    }

    // Check for duplicate image version
    Set<String> versions = new HashSet<>();
    for (ImageRampupRequest imageRampupRequest : imageRampupRequests) {
      if (!versions.contains(imageRampupRequest.getImageVersion())) {
        versions.add(imageRampupRequest.getImageVersion());
      } else {
        throw new ImageMgmtValidationException(String.format("Duplicate image version: %s.",
            imageRampupRequest.getImageVersion()));
      }
    }

    // check for stability tag and ramp up percentage
    for (ImageRampupRequest imageRampupRequest : imageRampupRequests) {
      if (StabilityTag.UNSTABLE.equals(imageRampupRequest.getStabilityTag())
          && imageRampupRequest.getRampupPercentage() != 0) {
        throw new ImageMgmtValidationException(String.format("The image version: %s is marked as "
                + "UNSTABLE in the input and hence the rampup percentage must be 0.",
            imageRampupRequest.getImageVersion()));
      }
    }
    return true;
  }
}

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
package azkaban.imagemgmt.rampup;

import azkaban.imagemgmt.daos.ImageRampupDao;
import azkaban.imagemgmt.daos.ImageTypeDao;
import azkaban.imagemgmt.daos.ImageVersionDao;
import azkaban.imagemgmt.exeception.ImageMgmtException;
import azkaban.imagemgmt.models.ImageRampup;
import azkaban.imagemgmt.models.ImageType;
import azkaban.imagemgmt.models.ImageVersion;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is responsible for fetching the version of the available images based on currently
 * active rampup plan and rampup details or the version which is already ramped up and active. Here
 * is the version selection process for an image type - 1. Fetch the rampup details for the given
 * image types or for all the image types (Two such methods are provided). 2. Sort the ramp up data
 * in ascending order of rampup percentage. 3. Generate a random number between 1 to 100 both
 * inclusive. Let us say the number the number generated is 60. 4. Let us say there are three
 * versions 1.1.1, 1.1.2 & and 1.1.3 with rampup percantages 10, 30 and 60 respectively. 5. The
 * above percentage fors three ranges [1 - 10], [11 - 40] & [41 - 100]. The random humber 60 belongs
 * to the last range i.e. [41 - 100] and hence version 1.1.3 will be selected. If random number
 * generated is 22 then version 1.1.2 will be selected and so on. 6. If there is no active rampup
 * plan for an image type or in the active plan if the version is marked unstable or deprecated, and
 * latest active version will be selected from the image_verions table. 7. If there is no active
 * version in the image_versions table, it will throw appropriate error message mentioning could not
 * select version for the image type and the whole process would fail. 8. Follow the rampup
 * procedure to elect a new version from the image_versions table for the failed image type.
 */
@Singleton
public class ImageRampupManagerImpl implements ImageRampupManger {

  private static final Logger log = LoggerFactory.getLogger(ImageRampupManagerImpl.class);
  private final ImageTypeDao imageTypeDao;
  private final ImageVersionDao imageVersionDao;
  private final ImageRampupDao imageRampupDao;

  @Inject
  public ImageRampupManagerImpl(final ImageRampupDao imageRampupDao,
      final ImageVersionDao imageVersionDao,
      final ImageTypeDao imageTypeDao) {
    this.imageRampupDao = imageRampupDao;
    this.imageVersionDao = imageVersionDao;
    this.imageTypeDao = imageTypeDao;
  }

  @Override
  public Map<String, String> fetchAllImageTypesVersion() throws ImageMgmtException {
    Map<String, List<ImageRampup>> imageTypeRampups = imageRampupDao.fetchAllImageTypesRampup();
    List<ImageType> imageTypeList = imageTypeDao.getAllImageTypes();
    Set<String> imageTypes = new TreeSet<>();
    for (ImageType imageType : imageTypeList) {
      imageTypes.add(imageType.getName());
    }
    return this.processAndGetVersionForImageTypes(imageTypes, imageTypeRampups);
  }

  @Override
  public Map<String, String> fetchVersionByImageTypes(Set<String> imageTypes)
      throws ImageMgmtException {
    Map<String, List<ImageRampup>> imageTypeRampups = imageRampupDao
        .fetchRampupByImageTypes(imageTypes);
    return this.processAndGetVersionForImageTypes(imageTypes, imageTypeRampups);
  }

  /**
   * This method processes image type rampup details for the image type and selects a version for
   * the image type. Here is the version selection process for an image type 1. Sort the ramp up
   * data in the ascending order of rampup percentage. 2. Generate a random number between 1 to 100
   * both inclusive. Let us say the number the number generated is 60. 3. Let us say there are three
   * versions 1.1.1, 1.1.2 & and 1.1.3 with rampup percantages 10, 30 & 60 respectively. 4. The
   * above percentage fors three ranges [1 - 10], [11 - 40] & [41 - 100]. The random humber 60
   * belongs to the last range i.e. [41 - 100] and hence version 1.1.3 will be selected. If random
   * number generated is 22 then version 1.1.2 will be selected and so on. 5. If there is no active
   * rampup plan for an image type or in the active plan if the version is marked unstable or
   * deprecated, and latest active version will be selected from the image_verions table. 6. If
   * there is no active version in the image_versions table, it will throw appropriate error message
   * mentioning could not select version for the image type and the whole process would fail. 7.
   * Follow the rampup procedure to elect a new version from the image_versions table for the ailed
   * image type.
   *
   * @param imageTypes       - set of specified image types
   * @param imageTypeRampups - contains rampup list for an image type
   * @return Map<String, String>
   */
  private Map<String, String> processAndGetVersionForImageTypes(Set<String> imageTypes,
      Map<String, List<ImageRampup>> imageTypeRampups) {
    Set<String> imageTypeSet = imageTypeRampups.keySet();
    log.info("Found active rampup for the image types {} ", imageTypeSet);
    Iterator<String> iterator = imageTypeSet.iterator();
    Map<String, String> imageTypeVersionMap = new TreeMap<>();
    while (iterator.hasNext()) {
      String imageTypeName = iterator.next();
      List<ImageRampup> imageRampupList = imageTypeRampups.get(imageTypeName);
      Collections.sort(imageRampupList, this.getRampupPercentageComparator());
      int prevRampupPercentage = 0;
      int nextRandom = this.getRandomNumberInRange(1, 100);
      for (ImageRampup imageRampup : imageRampupList) {
        int rampupPercentage = imageRampup.getRampupPercentage();
        if (nextRandom >= prevRampupPercentage + 1
            && nextRandom <= prevRampupPercentage + rampupPercentage) {
          imageTypeVersionMap.put(imageTypeName, imageRampup.getImageVersion());
          log.info("The image version {} is selected for image type {} with rampup percentage {}",
              imageRampup.getImageVersion(), imageTypeName, rampupPercentage);
          break;
        }
        prevRampupPercentage = rampupPercentage;
      }
    }

    /**
     * Fetching the latest active image version from image_versions table for the remaining image
     * types for which there is no active rampup plan or the versions are marrked as
     * unstable/deprecated in the active plan.
     */
    Set<String> rampedImageTypes = imageTypeRampups.keySet();
    Set<String> remainingImageTypes = new TreeSet<>(imageTypes);
    remainingImageTypes.removeAll(rampedImageTypes);
    if (!rampedImageTypes.isEmpty()) {
      List<ImageVersion> imageVersions =
          imageVersionDao.getActiveVersionByImageTypes(remainingImageTypes);
      if (imageVersions != null && !imageVersions.isEmpty()) {
        for (ImageVersion imageVersion : imageVersions) {
          imageTypeVersionMap.put(imageVersion.getName(), imageVersion.getVersion());
        }
      }
    }

    // For the leftover image types throw exception with appropriate error message.
    remainingImageTypes = new TreeSet<>(imageTypes);
    remainingImageTypes.removeAll(imageTypeVersionMap.keySet());
    if (!remainingImageTypes.isEmpty()) {
      throw new ImageMgmtException("Could not fetch version for below image types. Reasons: "
          + " 1. There is not active rampup plan in the image_rampup_plan table. 2. There is no "
          + " acitve version in the image_versions table. Image Types: " + remainingImageTypes);
    }

    return imageTypeVersionMap;
  }

  /**
   * Generate random number between min and max both inclusive
   *
   * @param min
   * @param max
   * @return int
   */
  private int getRandomNumberInRange(int min, int max) {
    if (min >= max) {
      throw new IllegalArgumentException("Max must be greater than min");
    }
    Random r = new Random();
    return r.nextInt((max - min) + 1) + min;
  }

  /**
   * Return rampup percentage comparator
   *
   * @return Comparator<ImageRampup>
   */
  private Comparator<ImageRampup> getRampupPercentageComparator() {
    return Comparator.comparingInt(ImageRampup::getRampupPercentage);
  }
}

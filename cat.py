import cv2
import numpy as np
import os
import time

class VideoToAscii:
    def __init__(self, video_path, width=100):
        self.video_capture = cv2.VideoCapture(video_path)
        self.width = width
        self.ascii_chars = "@#S%?*+;:,.. "

    def __pixel_to_ascii(self, pixel_value, is_mask):
        if is_mask == 0: 
            return " "
        index = int((pixel_value / 255) * (len(self.ascii_chars) - 2))
        return self.ascii_chars[index]

    def __process_frame(self, frame):
        height, original_width = frame.shape[:2]
        aspect_ratio = height / original_width
        new_height = int(self.width * aspect_ratio * 0.5)
        frame = cv2.resize(frame, (self.width, new_height))

        hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
        
        lower_green = np.array([35, 40, 40])
        upper_green = np.array([85, 255, 255])
        
        mask = cv2.inRange(hsv, lower_green, upper_green)
        mask_inv = cv2.bitwise_not(mask) 

        gray_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)

        ascii_frame = ""
        for i in range(new_height):
            for j in range(self.width):
                pixel_val = gray_frame[i, j]
                is_cat = mask_inv[i, j]
                ascii_frame += self.__pixel_to_ascii(pixel_val, is_cat)
            ascii_frame += "\n"
        
        return ascii_frame

    def display_video_ascii(self):
        try:
            while True:
                ret, frame = self.video_capture.read()
                if not ret:
                    break
                
                ascii_str = self.__process_frame(frame)
                
                print("\033c", end="", flush=True)
                print(ascii_str, flush=True)
                
                time.sleep(0.03)
        finally:
            self.video_capture.release()

if __name__ == "__main__":
    video_path = "cat.mp4"
    if os.path.exists(video_path):
        converter = VideoToAscii(video_path, width=120)
        converter.display_video_ascii()
    else:
        print("Không tìm thấy file video!")
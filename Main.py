import os
import tkinter as tk
from tkinter import filedialog, messagebox
from pydub import AudioSegment
from mutagen.id3 import ID3, GEOB
from mutagen.mp3 import MP3

# 无ffmpeg的打包指令：pyinstaller --onefile --windowed Main.py
# 包含ffmpeg打包指令：pyinstaller --onedir --add-binary="C:/Users/Apermesa/Downloads/Compressed/ffmpeg-2024-12-19-git-494c961379-essentials_build/bin/ffmpeg.exe;." --windowed Main.py
# 若要打包无ffmpeg版本，则注释下面这两行
# ffmpeg_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ffmpeg.exe")
# AudioSegment.converter = ffmpeg_path
def write_file(mp3Path, fileName):
    folder = "/".join(mp3Path.split("/")[:-1])
    with open(folder + "/output.mp3", "wb") as f:
        with open(mp3Path, "rb") as f2:
            f.write(f2.read())
    id3 = ID3(folder + "/output.mp3")
    with open(fileName, "rb") as f:
        data = f.read()
    geob = GEOB(mime="obj/e621", filename=fileName, data=data, desc=fileName)
    id3.add(geob)
    id3.save()
    print(id3.items())
    return id3.items()

def read_file(mp3Path):
    # 将所有文件解压到output文件夹中
    id3 = ID3(mp3Path)
    folder = "/".join(mp3Path.split("/")[:-1])
    print("Extracted files:\n")
    for item in id3.items():
        if isinstance(item[1], GEOB):
            if item[1].mime == "obj/e621":
                import os
                try:
                    os.makedirs(folder + "/output")
                except:
                    pass
                with open(folder + "/output/"+item[1].filename.split("/")[-1], "wb") as f:
                    f.write(item[1].data)
                print(folder + "/output/"+item[1].filename.split("/")[-1]+"\n")
def generate_empty_audio(output_file, file_name):
    """
    生成一个无声的短音频文件，包含标准 MP3 元数据
    """
    try:
        # 创建 1 秒无声音频
        silent_audio = AudioSegment.silent(duration=1000)  # 1000 毫秒 = 1 秒
        # 设置必要的 MP3 元数据
        silent_audio.export(output_file, format="mp3", tags={
            "title": file_name,
            "artist": "Apermesa",
            "album": "Half Life 3",
            "year": "2024"
        })
        return True
    except Exception as e:
        messagebox.showerror("错误", f"生成空音频失败：{str(e)}")
        return False

def embed_file_in_audio(file_path):
    """
    将任意文件嵌入到生成的无声音频文件中
    """
    file_name_with_extension = os.path.basename(file_path)  # 完整文件名
    output_audio_file = os.path.join(os.path.dirname(file_path), "temp_silent.mp3")
    if not generate_empty_audio(output_audio_file, file_name_with_extension):
        return

    output_file = os.path.join(os.path.dirname(file_path),
                               os.path.splitext(file_name_with_extension)[0] + " (embedded).mp3")
    try:
        write_file(output_audio_file, file_path)
        os.remove(output_audio_file)  # 删除临时音频文件
        messagebox.showinfo("完成", f"文件已嵌入！文件保存为：{output_file}")
    except Exception as e:
        messagebox.showerror("错误", f"嵌入失败：{str(e)}")

def embed_file_in_existing_audio(audio_file_path, file_to_embed_path):
    """
    将任意文件嵌入到现有音频文件中，并保持音频可正常播放
    """
    try:
        write_file(audio_file_path, file_to_embed_path)
        messagebox.showinfo("完成", f"文件已嵌入！文件保存为：output.mp3")
    except Exception as e:
        messagebox.showerror("错误", f"嵌入失败：{str(e)}")

def extract_file_from_audio(input_file):
    """
    从伪装文件中提取嵌入的文件，并恢复其原始文件名
    """
    try:
        read_file(input_file)
        messagebox.showinfo("完成", f"文件已提取！文件保存在output文件夹中")
    except Exception as e:
        messagebox.showerror("错误", f"提取失败：{str(e)}")

def empty_audio_embed_action():
    """选择文件并嵌入到生成的无声音频中"""
    file_path = filedialog.askopenfilename(title="选择文件", filetypes=[("所有文件", "*.*")])
    if not file_path:
        return

    embed_file_in_audio(file_path)

def existing_audio_embed_action():
    """选择音频文件和要嵌入的文件，并进行嵌入"""
    audio_file_path = filedialog.askopenfilename(title="选择音频文件", filetypes=[("音频文件", "*.mp3")])
    if not audio_file_path:
        return

    file_to_embed_path = filedialog.askopenfilename(title="选择要嵌入的文件", filetypes=[("所有文件", "*.*")])
    if not file_to_embed_path:
        return

    embed_file_in_existing_audio(audio_file_path, file_to_embed_path)

def extract_action():
    """选择伪装文件并提取嵌入的文件"""
    input_file = filedialog.askopenfilename(title="选择伪装文件", filetypes=[("音频文件", "*.mp3")])
    if not input_file:
        return

    extract_file_from_audio(input_file)

# 创建主界面
root = tk.Tk()
root.title("文件伪装音频工具")
root.geometry("300x260")
root.resizable(False, False)

# 空音频嵌入按钮
empty_audio_embed_button = tk.Button(root, text="空音频嵌入", command=empty_audio_embed_action, height=2, width=20)
empty_audio_embed_button.pack(pady=10)

# 已有音频嵌入按钮
existing_audio_embed_button = tk.Button(root, text="已有音频嵌入", command=existing_audio_embed_action, height=2, width=20)
existing_audio_embed_button.pack(pady=10)

# 提取按钮
extract_button = tk.Button(root, text="提取", command=extract_action, height=2, width=20)
extract_button.pack(pady=10)

# 作者标签
author_label = tk.Label(root, text="作者：Apermesa", font=("Arial", 10))
author_label.pack(side="bottom", pady=10)

# 运行主循环
root.mainloop()

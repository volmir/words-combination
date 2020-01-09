<template>
    <div>
        

                        
<h4>Игра «Составь слова из слов»</h4>

<div class="alert alert-success alert-dismissible fade show" role="alert">
  Для начала игры введите корректное слово
  <button type="button" class="close" data-dismiss="alert" aria-label="Close">
    <span aria-hidden="true">&times;</span>
  </button>
</div>

<div class="alert alert-secondary well" role="alert">
    <p>
        <span>Введите слово:</span>
    </p>
    <form class="form-inline" method="post" action="/game">
        <div class="form-group">
            <input type="text" class="form-control form-control-lg margin-right" placeholder="" name="word" value="" maxlength="30" autocomplete="off">
        </div>
        <button class="btn btn-primary btn-lg" id="start_search" type="submit">Начать игру</button>
    </form>
</div>

<div class="alert alert-secondary well" role="alert">
    <p>
        <span>Начать новую игру (<i>со случайным словом</i>):</span>
    </p>
    <form class="form-inline" method="post" action="/game">
        <input type="hidden" name="word" class="random_word" value="">
                <button type="button" class="btn btn-outline-secondary margin-right" data-word="роскошество">РОСКОШЕСТВО</button> 
                <button type="button" class="btn btn-outline-secondary margin-right" data-word="нагреватель">НАГРЕВАТЕЛЬ</button> 
                <button type="button" class="btn btn-outline-secondary margin-right" data-word="трипаносома">ТРИПАНОСОМА</button> 
                <button type="button" class="btn btn-outline-secondary margin-right" data-word="исчёркивание">ИСЧЁРКИВАНИЕ</button>
            </form>    
</div>

    <h4>Об игре</h4>
      
<p>    
Игра <b>"Составь слова"</b> предлагает игрокам известную головоломку, в которой нужно 
составлять разные слова из одного длинного слова. 
</p>
<p>
Соревнуйтесь с друзьями в количестве сложенных слов и использованных букв, и узнайте кто более смышлёный.
    </p>
    
    

<Waiting/>       


    </div>
</template>

<script>
import Waiting from './modal/Waiting.vue';

export default {
    components: {
        Waiting
    }    
}
</script>

<style scoped>

</style>